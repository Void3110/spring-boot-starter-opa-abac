package dev.dmitriikonovalov.opaabac.data.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.model.AbstractHierarchicalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link AncestorResolver#subtreeOf} for <strong>both impls</strong> against a
 * <strong>real Postgres with the {@code ltree} extension</strong> (never H2 — {@code ltree}, the {@code <@}
 * descendant operator and recursive {@code parent_id} walks are Postgres-specific). Seeds a
 * {@code catalog → category → product} tree plus a <em>sibling</em> catalog subtree in a single {@code node}
 * table (carrying both a real {@code ltree path} column and a {@code parent_type}/{@code parent_id} adjacency),
 * then runs the generated {@link Specification} and asserts the <strong>exact surviving row set</strong>:
 *
 * <ul>
 *   <li>I1 — {@code LtreeAncestorResolver.subtreeOf(catalog, C)} selects all of C's descendants
 *       (root-inclusive) via a single {@code path <@} predicate, and <em>excludes</em> the sibling subtree;</li>
 *   <li>I2 — {@code RecursiveCteAncestorResolver.subtreeOf(catalog, C)} selects the same row set via the
 *       bounded {@code id IN (…)} downward walk;</li>
 *   <li>I3 — a {@code maxDepth} breach (CTE) collapses to the empty predicate (fail-closed) — never the whole
 *       table; an ltree resolver with no path / over-deep root path likewise returns nothing.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SubtreeOfIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("subtreetest")
            .withUsername("subtreetest")
            .withPassword("subtreetest");

    static {
        POSTGRES.start();
        try (Connection c = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement st = c.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS ltree");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to create ltree extension", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private NodeRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    // Tree under test:  catalog C → category K1 → {product P1, product P2}
    //                   catalog C → category K2 (sibling under the SAME catalog)
    //                   catalog D → category K3 (a SEPARATE catalog subtree — must be excluded)
    private UUID catalogC;
    private UUID catK1;
    private UUID prodP1;
    private UUID prodP2;
    private UUID catK2;
    private UUID catalogD;
    private UUID catK3;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        catalogC = UUID.randomUUID();
        catK1 = UUID.randomUUID();
        prodP1 = UUID.randomUUID();
        prodP2 = UUID.randomUUID();
        catK2 = UUID.randomUUID();
        catalogD = UUID.randomUUID();
        catK3 = UUID.randomUUID();

        save("catalog", catalogC, null);
        save("category", catK1, new ParentRef("catalog", catalogC.toString()));
        save("product", prodP1, new ParentRef("category", catK1.toString()));
        save("product", prodP2, new ParentRef("category", catK1.toString()));
        save("category", catK2, new ParentRef("catalog", catalogC.toString()));
        save("catalog", catalogD, null);
        save("category", catK3, new ParentRef("catalog", catalogD.toString()));
    }

    @Test // I1 — ltree subtreeOf(catalog C): root-inclusive subtree, sibling catalog D excluded
    void ltree_subtreeOf_selectsWholeSubtree_excludesSibling() {
        AncestorResolver ltree = new LtreeAncestorResolver(ltreeSource(), 32);
        List<UUID> ids = idsMatching(ltree.subtreeOf("catalog", catalogC.toString()));
        assertThat(ids).containsExactlyInAnyOrder(catalogC, catK1, prodP1, prodP2, catK2);
        assertThat(ids).doesNotContain(catalogD, catK3);
    }

    @Test // I2 — CTE subtreeOf(catalog C): same row set via the bounded id IN downward walk
    void cte_subtreeOf_selectsSameSet() {
        AncestorResolver cte =
                new RecursiveCteAncestorResolver(cteParentSource(), cteDescendantSource(), 32);
        List<UUID> ids = idsMatching(cte.subtreeOf("catalog", catalogC.toString()));
        assertThat(ids).containsExactlyInAnyOrder(catalogC, catK1, prodP1, prodP2, catK2);
        assertThat(ids).doesNotContain(catalogD, catK3);
    }

    @Test // I1/I2 — both impls agree on the same root
    void bothImplsAgreeOnSubtree() {
        AncestorResolver ltree = new LtreeAncestorResolver(ltreeSource(), 32);
        AncestorResolver cte =
                new RecursiveCteAncestorResolver(cteParentSource(), cteDescendantSource(), 32);
        assertThat(idsMatching(ltree.subtreeOf("catalog", catalogC.toString())))
                .containsExactlyInAnyOrderElementsOf(
                        idsMatching(cte.subtreeOf("catalog", catalogC.toString())));
    }

    @Test // subtreeOf a deeper root (a category) selects only that category's branch
    void subtreeOf_categoryRoot_selectsOnlyThatBranch() {
        AncestorResolver ltree = new LtreeAncestorResolver(ltreeSource(), 32);
        AncestorResolver cte =
                new RecursiveCteAncestorResolver(cteParentSource(), cteDescendantSource(), 32);
        assertThat(idsMatching(ltree.subtreeOf("category", catK1.toString())))
                .containsExactlyInAnyOrder(catK1, prodP1, prodP2);
        assertThat(idsMatching(cte.subtreeOf("category", catK1.toString())))
                .containsExactlyInAnyOrder(catK1, prodP1, prodP2);
    }

    @Test // I3 — CTE depth breach → empty predicate (fail-closed), never the whole table
    void cte_depthBreach_failsClosedToEmpty() {
        // catalog C's subtree is 3 levels deep (catalog→category→product); bound it to 1 level → breach.
        AncestorResolver cte =
                new RecursiveCteAncestorResolver(cteParentSource(), cteDescendantSource(), 1);
        assertThat(idsMatching(cte.subtreeOf("catalog", catalogC.toString()))).isEmpty();
    }

    @Test // I3 — CTE with NO descendant source → empty predicate (no widening, safe)
    void cte_noDescendantSource_failsClosedToEmpty() {
        AncestorResolver cte = new RecursiveCteAncestorResolver(cteParentSource(), 32); // no down-source
        assertThat(idsMatching(cte.subtreeOf("catalog", catalogC.toString()))).isEmpty();
    }

    @Test // I3 — ltree with a missing root path → empty predicate (fail-closed)
    void ltree_missingRootPath_failsClosedToEmpty() {
        AncestorResolver ltree = new LtreeAncestorResolver(ltreeSource(), 32);
        assertThat(idsMatching(ltree.subtreeOf("catalog", UUID.randomUUID().toString()))).isEmpty();
    }

    @Test // I3 — ltree with a too-deep root path → empty predicate (malformed lineage, fail-closed)
    void ltree_overDeepRootPath_failsClosedToEmpty() {
        // catalog C's own path is 1 label; bound maxDepth to 0-equivalent by making any real path "too deep".
        // A category root path is 2 labels; with maxDepth=1 it exceeds the bound → empty.
        AncestorResolver ltree = new LtreeAncestorResolver(ltreeSource(), 1);
        assertThat(idsMatching(ltree.subtreeOf("category", catK1.toString()))).isEmpty();
    }

    // --- helpers -------------------------------------------------------------

    /** Save a node deriving its ltree path from its parent's path (read from the just-saved rows). */
    private void save(String type, UUID id, ParentRef parent) {
        String selfLabel = HierarchyLabels.label(type, id.toString());
        String path;
        if (parent == null) {
            path = selfLabel;
        } else {
            String parentPath = readPath(UUID.fromString(parent.id()))
                    .orElseThrow(() -> new IllegalStateException("parent not seeded: " + parent));
            path = parentPath + "." + selfLabel;
        }
        NodeEntity e = new NodeEntity(id, type, parent);
        e.setPath(path);
        repository.saveAndFlush(e);
    }

    private List<UUID> idsMatching(Specification<NodeEntity> spec) {
        return repository.findAll(spec).stream().map(NodeEntity::getId).toList();
    }

    private LtreePathSource ltreeSource() {
        return (type, id) -> readPath(UUID.fromString(id));
    }

    /** Live parent-id adjacency (up). */
    private ParentLinkSource cteParentSource() {
        return (type, id) -> {
            NodeEntity row = repository.findById(UUID.fromString(id)).orElse(null);
            return row == null ? Optional.empty() : row.abacParent();
        };
    }

    /** Live parent-id adjacency (down) — the immediate children of (type,id), any child type. */
    private DescendantIdSource cteDescendantSource() {
        return (type, id) -> {
            List<ParentRef> children = new ArrayList<>();
            for (NodeEntity row : repository.findAll()) {
                Optional<ParentRef> p = row.abacParent();
                if (p.isPresent() && p.get().id().equals(id)) {
                    children.add(new ParentRef(row.abacResourceType(), row.abacResourceId()));
                }
            }
            return children;
        };
    }

    private Optional<String> readPath(UUID id) {
        @SuppressWarnings("unchecked")
        Optional<String> path = entityManager
                .createNativeQuery("SELECT path::text FROM node WHERE id = :id")
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .map(o -> (String) o);
        return path;
    }

    // --- fixtures ------------------------------------------------------------

    @Entity
    @Table(name = "node")
    static class NodeEntity extends AbstractHierarchicalEntity {

        @Column(name = "node_type", nullable = false)
        private String nodeType;

        @Column(name = "parent_type")
        private String parentType;

        @Column(name = "parent_ref_id")
        private String parentRefId;

        NodeEntity() {
            // JPA
        }

        NodeEntity(UUID id, String nodeType, ParentRef parent) {
            super(id);
            this.nodeType = nodeType;
            if (parent != null) {
                this.parentType = parent.type();
                this.parentRefId = parent.id();
            }
        }

        @Override
        public String abacResourceType() {
            return nodeType;
        }

        @Override
        public Optional<ParentRef> abacParent() {
            return parentType == null ? Optional.empty() : Optional.of(new ParentRef(parentType, parentRefId));
        }
    }

    interface NodeRepository
            extends JpaRepository<NodeEntity, UUID>, JpaSpecificationExecutor<NodeEntity> {}

    @SpringBootApplication
    @EntityScan(basePackageClasses = NodeEntity.class)
    @EnableJpaRepositories(considerNestedRepositories = true)
    @EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
    static class TestApp {
        @Bean
        DateTimeProvider auditingDateTimeProvider() {
            return () -> java.util.Optional.of(java.time.OffsetDateTime.now());
        }

        @Bean
        AuditorAware<UUID> auditorAware() {
            return java.util.Optional::empty;
        }
    }
}
