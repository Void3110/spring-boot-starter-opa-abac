package dev.dmitriikonovalov.opaabac.data.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolutionException;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.HierarchicalPathMaintainer;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreeAncestorResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreePathSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link AbstractHierarchicalEntity} + {@link HierarchicalPathMaintainer} against a
 * <strong>real Postgres with the {@code ltree} extension</strong> (never H2). Uses {@link TransactionTemplate}
 * for <em>committed</em> units of work (rather than {@code @DataJpaTest}'s rollback wrapper) so the atomic
 * re-parent and its failure modes are observable across transactions, exactly as in production. Proves:
 *
 * <ul>
 *   <li>I6 — inserting a child derives the correct {@code path} from its parent;</li>
 *   <li>I7 — re-parenting a subtree rewrites <em>every</em> descendant's {@code path}, and the
 *       {@link LtreeAncestorResolver} returns the <em>new</em> chain;</li>
 *   <li>I8 — atomicity: a constraint violation <em>mid-rewrite</em> rolls the whole subtree UPDATE back —
 *       the tree is left unchanged (no half-rewrite);</li>
 *   <li>I9 — a re-parent under one's own descendant is rejected (cycle guard).</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
// Opt OUT of @DataJpaTest's per-test rollback transaction: this IT manages its own COMMITTED units of work
// via TransactionTemplate (so the atomic re-parent and its rollback are observable across transactions, and
// a separate-connection read sees committed state). Cleanup is explicit in @BeforeEach.
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class AbstractHierarchicalEntityIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hierarchytest")
            .withUsername("hierarchytest")
            .withPassword("hierarchytest");

    static {
        POSTGRES.start();
        // The ltree extension must exist before Hibernate creates a table with an ltree column.
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

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private LtreePathSource pathSource;
    private AncestorResolver resolver;

    // A 3-level tree: catalog → category → product, plus a second catalog to move under.
    private UUID catalogId;
    private UUID categoryId;
    private UUID productId;
    private UUID otherCatalogId;

    @BeforeEach
    void seed() throws SQLException {
        tx = new TransactionTemplate(txManager);
        // The maintainer reads parent paths within the SAME persistence context (so a just-flushed parent
        // is visible mid-transaction); assertions read committed state via a separate connection (readPath).
        pathSource = (type, id) -> {
            @SuppressWarnings("unchecked")
            Optional<String> path = entityManager
                    .createNativeQuery("SELECT path::text FROM node WHERE id = :id")
                    .setParameter("id", UUID.fromString(id))
                    .getResultStream()
                    .findFirst()
                    .map(o -> (String) o);
            return path;
        };
        // The resolver is only used in assertions, AFTER the unit-of-work commits and outside any tx, so it
        // reads committed state via the separate connection.
        resolver = new LtreeAncestorResolver((type, id) -> readPath(UUID.fromString(id)), 32);

        // committed clean slate
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.execute("DELETE FROM node");
        }

        catalogId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        productId = UUID.randomUUID();
        otherCatalogId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            HierarchicalPathMaintainer maintainer = newMaintainer();
            saveWithPath(maintainer, "catalog", catalogId, null);
            saveWithPath(maintainer, "catalog", otherCatalogId, null);
            saveWithPath(maintainer, "category", categoryId, new ParentRef("catalog", catalogId.toString()));
            saveWithPath(maintainer, "product", productId, new ParentRef("category", categoryId.toString()));
        });
    }

    @Test // I6 — inserting a child derives the correct path from its parent
    void insertDerivesPathFromParent() throws SQLException {
        String productPath = readPath(productId).orElseThrow();
        assertThat(productPath)
                .startsWith("catalog_" + hex(catalogId))
                .contains(".category_" + hex(categoryId))
                .endsWith(".product_" + hex(productId));
        assertThat(resolver.ancestorsOf("product", productId.toString()))
                .containsExactly(
                        new ParentRef("catalog", catalogId.toString()),
                        new ParentRef("category", categoryId.toString()));
    }

    @Test // I7 — re-parent rewrites the whole moved subtree; the resolver returns the NEW chain
    void reparentRewritesSubtreeAndResolverSeesNewChain() throws SQLException {
        String oldCategoryPath = readPath(categoryId).orElseThrow();
        int categoryVersionBefore = readVersion(categoryId);
        int productVersionBefore = readVersion(productId);

        int rewritten = tx.execute(status -> newMaintainer().reparent(
                "node", oldCategoryPath, Optional.of(new ParentRef("catalog", otherCatalogId.toString()))));
        assertThat(rewritten).isEqualTo(2); // the category + its product

        assertThat(readPath(categoryId).orElseThrow()).startsWith("catalog_" + hex(otherCatalogId));
        assertThat(readPath(productId).orElseThrow())
                .startsWith("catalog_" + hex(otherCatalogId))
                .endsWith(".product_" + hex(productId));
        // the rewrite bumps @Version on every rewritten row, so a pre-move optimistic snapshot conflicts
        assertThat(readVersion(categoryId)).isEqualTo(categoryVersionBefore + 1);
        assertThat(readVersion(productId)).isEqualTo(productVersionBefore + 1);
        // the resolver now returns the NEW root
        assertThat(resolver.ancestorsOf("product", productId.toString()))
                .containsExactly(
                        new ParentRef("catalog", otherCatalogId.toString()),
                        new ParentRef("category", categoryId.toString()));
    }

    @Test // I8a — pre-flight: a missing new-parent path throws BEFORE any row is touched
    void reparentMissingParentPathThrowsBeforeAnyWrite() throws SQLException {
        String categoryPathBefore = readPath(categoryId).orElseThrow();
        String productPathBefore = readPath(productId).orElseThrow();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> newMaintainer().reparent(
                        "node", categoryPathBefore,
                        Optional.of(new ParentRef("catalog", UUID.randomUUID().toString())))))
                .isInstanceOf(AncestorResolutionException.class);

        assertThat(readPath(categoryId)).contains(categoryPathBefore);
        assertThat(readPath(productId)).contains(productPathBefore);
    }

    @Test // I8 — atomicity: a UNIQUE(path) violation MID-rewrite rolls the WHOLE subtree UPDATE back
    void reparentIsAtomic_midRewriteFailureLeavesTreeUnchanged() throws SQLException {
        String categoryPathBefore = readPath(categoryId).orElseThrow();
        String productPathBefore = readPath(productId).orElseThrow();

        // Seed a decoy under the OTHER catalog whose path is EXACTLY the path the product WOULD get after
        // the move → the subtree UPDATE collides on UNIQUE(path) and the whole statement must roll back.
        String otherCatalogPath = readPath(otherCatalogId).orElseThrow();
        String collidingProductPath = otherCatalogPath
                + ".category_" + hex(categoryId) + ".product_" + hex(productId);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
                        "INSERT INTO node(id,version,created_at,tags,node_type,path) "
                                + "VALUES (?, 0, now(), CAST('{}' AS jsonb), 'decoy', CAST(? AS ltree))")
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, collidingProductPath)
                .executeUpdate());

        // The re-parent's single subtree UPDATE will violate UNIQUE(path) when it rewrites the product row.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> newMaintainer().reparent(
                        "node", categoryPathBefore,
                        Optional.of(new ParentRef("catalog", otherCatalogId.toString())))))
                .isInstanceOf(Exception.class);

        // The tree is exactly as before — neither the category nor the product moved (no half-rewrite).
        assertThat(readPath(categoryId)).contains(categoryPathBefore);
        assertThat(readPath(productId)).contains(productPathBefore);
    }

    @Test // I9 — re-parenting under one's own descendant is rejected (cycle guard)
    void reparentUnderOwnDescendantRejected() throws SQLException {
        String catalogPath = readPath(catalogId).orElseThrow();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> newMaintainer().reparent(
                        "node", catalogPath, Optional.of(new ParentRef("product", productId.toString())))))
                .isInstanceOf(AncestorResolutionException.class)
                .hasMessageContaining("descendant");
    }

    // --- helpers -------------------------------------------------------------

    private HierarchicalPathMaintainer newMaintainer() {
        return new HierarchicalPathMaintainer(entityManager, pathSource);
    }

    private void saveWithPath(HierarchicalPathMaintainer maintainer, String type, UUID id, ParentRef parent) {
        NodeEntity e = new NodeEntity(id, type, parent);
        maintainer.assignPath(e);
        repository.saveAndFlush(e);
    }

    private int readVersion(UUID id) {
        try (Connection c = dataSource.getConnection();
                var ps = c.prepareStatement("SELECT version FROM node WHERE id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("no node row for " + id);
                }
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<String> readPath(UUID id) {
        try (Connection c = dataSource.getConnection();
                var ps = c.prepareStatement("SELECT path::text FROM node WHERE id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    // --- fixtures ------------------------------------------------------------

    @Entity
    @Table(
            name = "node",
            uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                    name = "uq_node_path", columnNames = "path"))
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

    interface NodeRepository extends JpaRepository<NodeEntity, UUID> {}

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
