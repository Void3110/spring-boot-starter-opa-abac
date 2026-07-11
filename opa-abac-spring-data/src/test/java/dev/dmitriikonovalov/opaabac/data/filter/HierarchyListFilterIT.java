package dev.dmitriikonovalov.opaabac.data.filter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.Condition;
import dev.dmitriikonovalov.opaabac.core.Conjunction;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.HierarchyLabels;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreeAncestorResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreePathSource;
import dev.dmitriikonovalov.opaabac.data.hierarchy.SubtreeSpecResolver;
import dev.dmitriikonovalov.opaabac.data.model.AbstractHierarchicalEntity;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
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
 * The consolidated end-to-end integration test for the hierarchy-aware list filter (Slice 5.5-B) against a
 * <strong>real Postgres with the {@code ltree} extension + JSONB</strong> (never H2). It composes the shipped
 * {@code AbacQueryService} (4-arg, T3) with a real {@code SubtreeSpecResolver} (T2) over an
 * {@code LtreeAncestorResolver} {@code subtreeOf} (T1), proving the headline behaviors as exact surviving
 * row sets (QA cases I4–I8):
 *
 * <ul>
 *   <li>I4 — <b>widening</b>: a subject with no leaf-tag match but an inheritable catalog grant sees the
 *       whole catalog subtree (the headline);</li>
 *   <li>I5 — <b>two subjects → different sets</b>: a region-gated role sees only its region's rows; the
 *       inheritable-grant role sees all — a different SQL cut;</li>
 *   <li>I6 — <b>{@code notDenied} narrowing</b>: an {@code abac_deny=true} row is excluded even from the
 *       widened set (MANDATORY);</li>
 *   <li>I7 — <b>AND-with-scope no-leak</b>: subtree widening for catalog C AND a foreign {@code catalogId}
 *       scope → empty — the widening cannot escape the caller's scope (MANDATORY, the load-bearing
 *       invariant);</li>
 *   <li>I8 — <b>re-parent on list</b>: move a category subtree from C to D, re-query → the moved rows leave
 *       the C-widened list and enter the D-widened list (MANDATORY).</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
// Pin the configuration explicitly so @DataJpaTest does not package-scan for a @SpringBootConfiguration —
// this filter test package holds several, which would be ambiguous (Found multiple @SpringBootConfiguration).
@org.springframework.test.context.ContextConfiguration(classes = HierarchyListFilterIT.TestApp.class)
class HierarchyListFilterIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hierlist")
            .withUsername("hierlist")
            .withPassword("hierlist");

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

    private static final String SUBJ_REGION = "subject-region";
    private static final String SUBJ_INHERIT = "subject-inherit";
    // inheritance declaration: a category inherits from a catalog ancestor (opt-in).
    private static final Map<String, List<String>> INHERITABLE = Map.of("category", List.of("catalog"));

    @Autowired
    private CatRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    private final ResidualSpecificationFactory factory = new ResidualSpecificationFactory();

    // Tree:  catalog C → { cat-emea (region=emea), cat-apac (region=apac), cat-deny (region=emea, abac_deny) }
    //        catalog D → { cat-d (region=apac) }   (a foreign scope)
    private UUID catalogC;
    private UUID catalogD;
    private UUID catEmea;
    private UUID catApac;
    private UUID catDeny;
    private UUID catD;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        catalogC = UUID.randomUUID();
        catalogD = UUID.randomUUID();
        // The catalogs are roots; we only persist the leaf categories here (the list filters categories),
        // but the categories' ltree paths encode the catalog root so subtreeOf(catalog, C) selects them.
        catEmea = saveCategory(catalogC, Map.of("region", "emea"));
        catApac = saveCategory(catalogC, Map.of("region", "apac"));
        catDeny = saveCategory(catalogC, Map.of("region", "emea", "abac_deny", true));
        catD = saveCategory(catalogD, Map.of("region", "apac"));
    }

    @Test // I4 + I5 — two subjects, same call: region-gated vs inheritable-grant → DIFFERENT sets
    void widening_twoSubjects_differentSets() {
        // Subject REGION: residual region=emea, no inheritable grant → only emea rows (minus deny).
        List<UUID> regionRows = listCategories(SUBJ_REGION, catalogC);
        // Subject INHERIT: inheritable catalog grant → the whole C subtree (minus deny).
        List<UUID> inheritRows = listCategories(SUBJ_INHERIT, catalogC);

        // region subject sees ONLY emea (cat-deny is region=emea but abac_deny → excluded by notDenied)
        assertThat(regionRows).containsExactlyInAnyOrder(catEmea);
        // inherit subject is WIDENED to the whole C subtree, minus the denied row → emea + apac (NOT deny)
        assertThat(inheritRows).containsExactlyInAnyOrder(catEmea, catApac);
        // a genuinely different SQL cut
        assertThat(inheritRows).isNotEqualTo(regionRows);
        // I4 — the inherit subject sees catApac, which its leaf-tags (region=emea residual) alone would NOT
        assertThat(inheritRows).contains(catApac);
    }

    @Test // I6 — notDenied narrowing: cat-deny (abac_deny=true) excluded even from the widened set (MANDATORY)
    void denyOverrides_excludesDeniedRowFromWidenedList() {
        List<UUID> inheritRows = listCategories(SUBJ_INHERIT, catalogC);
        assertThat(inheritRows).doesNotContain(catDeny);
        // and the region subject (whose residual region=emea WOULD match cat-deny's region) also excludes it
        assertThat(listCategories(SUBJ_REGION, catalogC)).doesNotContain(catDeny);
    }

    @Test // I7 — AND-with-scope no-leak: widening for C cannot surface a row under a FOREIGN catalog D scope
    void noLeak_wideningCannotEscapeForeignScope() {
        // The inherit subject's role grants on catalog C; but the list is SCOPED to catalog D.
        // The subtreeSpec widens to C's subtree, but scope.and(...) restricts to D → the C rows cannot leak.
        List<UUID> rows = listCategoriesScopedTo(SUBJ_INHERIT, /*grantRoot*/ catalogC, /*scope*/ catalogD);
        // No catalog-C category leaks into the catalog-D-scoped list. The subtreeSpec is C's subtree, the
        // scope is catalog D → scope.and(...) makes the intersection empty: neither the C rows leak in, nor
        // does cat-d appear (the inherit subject's grant is on C, not D, so D's subtree is never widened in).
        assertThat(rows).doesNotContain(catEmea, catApac, catDeny, catD);
        assertThat(rows).isEmpty();
    }

    @Test // I8 — re-parent on list: move cat-apac from catalog C to catalog D, re-query both lists (MANDATORY)
    void reparent_movesRowBetweenWidenedLists() {
        // before: cat-apac is in C's widened list, not in D's.
        assertThat(listCategories(SUBJ_INHERIT, catalogC)).contains(catApac);
        assertThat(listCategoriesWithGrantRoot(SUBJ_INHERIT, catalogD, catalogD)).doesNotContain(catApac);

        // Re-parent: rewrite cat-apac's ltree path to live under catalog D (atomic single-row move here).
        reparentCategory(catApac, catalogD);

        // after: cat-apac leaves C's widened list and enters D's.
        assertThat(listCategories(SUBJ_INHERIT, catalogC)).doesNotContain(catApac);
        assertThat(listCategoriesWithGrantRoot(SUBJ_INHERIT, catalogD, catalogD)).contains(catApac);
    }

    // --- the list call under test --------------------------------------------

    /** List the categories under {@code catalogId} the subject may read (grant root == scope root). */
    private List<UUID> listCategories(String subjectId, UUID catalogId) {
        return listCategoriesWithGrantRoot(subjectId, catalogId, catalogId);
    }

    /** List scoped to {@code scopeCatalogId} but with the inheritable grant resolved on {@code grantRoot}. */
    private List<UUID> listCategoriesScopedTo(String subjectId, UUID grantRoot, UUID scopeCatalogId) {
        return listCategoriesWithGrantRoot(subjectId, scopeCatalogId, grantRoot);
    }

    private List<UUID> listCategoriesWithGrantRoot(String subjectId, UUID scopeCatalogId, UUID grantRoot) {
        AbacContext.Subject subject = new AbacContext.Subject(subjectId, List.of(), Map.of());
        ParentRef governingRoot = new ParentRef("catalog", grantRoot.toString());

        SubtreeSpecResolver subtreeResolver =
                new SubtreeSpecResolver(ancestorResolver(), roleSupplier(), INHERITABLE);
        Optional<Specification<CatEntity>> subtreeSpec =
                subtreeResolver.subtreeSpec(subject, "category", governingRoot, "read");

        AbacContext ctx = new AbacContext(
                subject,
                "category:read",
                new AbacContext.Resource("category", null, Map.of()),
                roleSupplier().lookup(subjectId, "catalog", grantRoot.toString()).orElse(null),
                Map.of());

        // The residual is tag-only (as category.rego's filter compiles): region=emea for SUBJ_REGION, the
        // fail-closed DENY_ALL for SUBJ_INHERIT (its role has no category:read tag grant — it relies entirely
        // on the inheritable catalog grant, exactly the scenario the subtreeSpec exists to cover).
        OpaClient client = compileStub(residualFor(subjectId));
        AbacQueryService service =
                new AbacQueryService(client, factory, AbacQueryService.PartialEvalSettings.defaults());

        Specification<CatEntity> scope =
                (root, q, cb) -> cb.equal(root.get("catalogId"), scopeCatalogId);
        return service.findAuthorized(repository, scope, ctx, subtreeSpec.orElse(null)).stream()
                .map(CatEntity::getId)
                .toList();
    }

    // --- collaborators (real, over the seeded DB) ----------------------------

    private AncestorResolver ancestorResolver() {
        // The catalog roots are not rows in the single `cat` table (only categories are listed), so a
        // catalog's path is synthesized from its label (catalog_<id>); a category's path is read from its
        // row. This mirrors the real app, where each type lives in its own table with a `path` column.
        LtreePathSource pathSource = (type, id) -> {
            if ("catalog".equals(type)) {
                return Optional.of(HierarchyLabels.label("catalog", id));
            }
            return readPath(UUID.fromString(id));
        };
        return new LtreeAncestorResolver(pathSource, 32);
    }

    /** SUBJ_INHERIT has an inheritable catalog grant (read on catalog); SUBJ_REGION has none. */
    private RoleDefinitionSupplier roleSupplier() {
        return (userId, type, id) -> {
            if (SUBJ_INHERIT.equals(userId) && "catalog".equals(type)) {
                return Optional.of(new RoleDefinition("inherit", Map.of(), Map.of("catalog", List.of("read"))));
            }
            return Optional.empty();
        };
    }

    /** The tag residual a subject's role compiles to (tag-only, as category.rego's filter does). */
    private static PartialResult residualFor(String subjectId) {
        if (SUBJ_REGION.equals(subjectId)) {
            return new PartialResult(
                    PartialResult.Decision.CONDITIONAL,
                    List.of(new Conjunction(List.of(
                            new Condition("tags.region", Condition.Operator.EQ, "emea")))));
        }
        // SUBJ_INHERIT: no tag grant at all → the filter rule compiles to DENY_ALL; the widening is the only
        // thing that lets it see rows (exactly what the subtreeSpec exists to provide).
        return PartialResult.denyAll();
    }

    // --- ltree path lookups / mutations --------------------------------------

    private Optional<String> readPath(UUID id) {
        @SuppressWarnings("unchecked")
        Optional<String> path = entityManager
                .createNativeQuery("SELECT path::text FROM cat WHERE id = :id")
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .map(o -> (String) o);
        return path;
    }

    /** Move a leaf category under a new catalog by rewriting its ltree path (single-row move). */
    private void reparentCategory(UUID categoryId, UUID newCatalogId) {
        String newPath = HierarchyLabels.label("catalog", newCatalogId.toString())
                + "." + HierarchyLabels.label("category", categoryId.toString());
        entityManager.createNativeQuery(
                        "UPDATE cat SET path = CAST(:path AS ltree), catalog_id = :cat WHERE id = :id")
                .setParameter("path", newPath)
                .setParameter("cat", newCatalogId)
                .setParameter("id", categoryId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    private UUID saveCategory(UUID catalogId, Map<String, Object> tags) {
        UUID id = UUID.randomUUID();
        String path = HierarchyLabels.label("catalog", catalogId.toString())
                + "." + HierarchyLabels.label("category", id.toString());
        CatEntity e = new CatEntity(id, catalogId);
        e.setTags(ResourceTags.fromMap(tags));
        e.setPath(path);
        return repository.saveAndFlush(e).getId();
    }

    private static OpaClient compileStub(PartialResult residual) {
        return new OpaClient() {
            @Override
            public boolean allow(AbacContext context) {
                return false;
            }

            @Override
            public PartialResult compile(AbacContext context) {
                return residual;
            }

            @Override
            public List<Boolean> allowAll(List<AbacContext> contexts) {
                return List.of();
            }
        };
    }

    // --- fixtures ------------------------------------------------------------

    @Entity
    @Table(name = "cat")
    static class CatEntity extends AbstractHierarchicalEntity {

        @Column(name = "catalog_id", nullable = false)
        private UUID catalogId;

        CatEntity() {
            // JPA
        }

        CatEntity(UUID id, UUID catalogId) {
            super(id);
            this.catalogId = catalogId;
        }

        @Override
        public String abacResourceType() {
            return "category";
        }

        @Override
        public Optional<ParentRef> abacParent() {
            return Optional.of(new ParentRef("catalog", catalogId.toString()));
        }
    }

    interface CatRepository extends JpaRepository<CatEntity, UUID>, JpaSpecificationExecutor<CatEntity> {}

    @SpringBootApplication
    @EntityScan(basePackageClasses = CatEntity.class)
    @EnableJpaRepositories(considerNestedRepositories = true)
    @EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
    static class TestApp {
        @Bean
        DateTimeProvider auditingDateTimeProvider() {
            return () -> Optional.of(java.time.OffsetDateTime.now());
        }

        @Bean
        AuditorAware<UUID> auditorAware() {
            return Optional::empty;
        }
    }
}
