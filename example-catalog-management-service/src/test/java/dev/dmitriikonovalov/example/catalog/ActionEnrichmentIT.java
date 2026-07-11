package dev.dmitriikonovalov.example.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductEntity;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Single-resource action-enrichment IT (Phase 6) — QA cases I3–I6, real Postgres.
 *
 * <p>Resolution is <strong>ON</strong> (the gate caches the resolved snapshot) and action-enrichment is
 * <strong>ON</strong>; the OPA stub is <em>context-aware</em> — its {@code allowAll} decides each
 * {@code (action, resolved-attributes)} the enrichment advice sends, so the {@code _actions} map provably
 * mirrors the resolved state per verb. The headline: a read-only subject's GET returns
 * {@code view:true, update:false, delete:false} on the <em>same</em> row (the honest map).
 */
@SpringBootTest(properties = {
    "catalog.role-source=none",
    "opa.abac.action-enrichment.enabled=true"
})
@Testcontainers
@AutoConfigureMockMvc
@Import(ActionEnrichmentIT.EnrichmentTestConfig.class)
class ActionEnrichmentIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-00000000be11");

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;
    @MockitoSpyBean CategoryRepository categories; // spied so I1 can prove the advice reused the gate's snapshot
    @Autowired ProductRepository products;
    @Autowired CatalogHierarchyService hierarchy;

    @BeforeEach
    void resetStub() {
        // Default: allow every per-verb enrichment context AND the gate's allow check.
        ProgrammableOpaClient.perContextRule = ctx -> true;
        ProgrammableOpaClient.captured.clear();
    }

    // --- I3: the honest map (the headline) -------------------------------------

    @Test // I3 — a read-only subject GETs a category → view:true, update/delete/assign-tags:false, same row
    void honestActionsMapOnSingleCategory() throws Exception {
        var catalog = seedCatalog();
        var emea = seedCategory(catalog.getId(), null, "emea-cat", Map.of("region", "emea"));
        // read-only: only the view verb is allowed; every mutating verb denied
        ProgrammableOpaClient.perContextRule = ctx -> ctx.action().endsWith(":view");

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), emea.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(emea.getId().toString()))
                .andExpect(jsonPath("$._actions.view").value(true))
                .andExpect(jsonPath("$._actions.update").value(false))
                .andExpect(jsonPath("$._actions.delete").value(false))
                .andExpect(jsonPath("$._actions.assign-tags").value(false));
    }

    @Test // I1 (single-GET clause) — the advice enriches from the gate's cached snapshot, NOT a re-load:
    // a getCategory loads the category exactly once (the resolver's findById), and the advice reads
    // _actions off the request-scoped cache without a second SELECT for that row.
    void singleGetEnrichesFromCacheWithoutSecondSelect() throws Exception {
        var catalog = seedCatalog();
        var emea = seedCategory(catalog.getId(), null, "emea-cat", Map.of("region", "emea"));
        ProgrammableOpaClient.perContextRule = ctx -> true;
        org.mockito.Mockito.clearInvocations(categories);

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), emea.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._actions.view").value(true));

        // exactly one load by id — the gate's resolver; the advice took the cache path, never re-loaded
        org.mockito.Mockito.verify(categories, org.mockito.Mockito.times(1)).findById(emea.getId());
    }

    @Test // I3 — a full-access subject → every category verb true
    void fullActionsMapWhenAllAllowed() throws Exception {
        var catalog = seedCatalog();
        var emea = seedCategory(catalog.getId(), null, "emea-cat", Map.of("region", "emea"));
        ProgrammableOpaClient.perContextRule = ctx -> true;

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), emea.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._actions.view").value(true))
                .andExpect(jsonPath("$._actions.update").value(true))
                .andExpect(jsonPath("$._actions.delete").value(true))
                .andExpect(jsonPath("$._actions.assign-tags").value(true));
    }

    @Test // I2/I3 — the map mirrors enforcement per the RESOLVED tags: a verb gated on region=emea
    void actionsMapMirrorsResolvedTags() throws Exception {
        var catalog = seedCatalog();
        var apac = seedCategory(catalog.getId(), null, "apac-cat", Map.of("region", "apac"));
        // update is allowed only when the resolved tags carry region=emea; view always allowed
        ProgrammableOpaClient.perContextRule = ctx -> ctx.action().endsWith(":view")
                || "emea".equals(ctx.resource().attributes().get("region"));

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), apac.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._actions.view").value(true))
                .andExpect(jsonPath("$._actions.update").value(false)); // apac ≠ emea → denied, honestly
    }

    // --- I4: deep product reflects the governing-root role ----------------------

    @Test // I4 — a product two levels deep: enrichment resolved ancestors; the verdict carries the chain
    void deepProductReflectsGoverningRootRole() throws Exception {
        var catalog = seedCatalog();
        var root = seedCategory(catalog.getId(), null, "root-cat", Map.of());
        var product = seedProduct(root.getId(), "widget");
        // allow product verbs only when the enrichment context carried the catalog root in its ancestors
        ProgrammableOpaClient.perContextRule = ctx -> ctx.resource().ancestors().stream()
                .anyMatch(p -> p.type().equals("catalog") && p.id().equals(catalog.getId().toString()));

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{cat}/products/{p}",
                        catalog.getId(), root.getId(), product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._actions.view").value(true))
                .andExpect(jsonPath("$._actions.update").value(true))
                .andExpect(jsonPath("$._actions.delete").value(true));
    }

    // --- I6: the verified verb sets (catalog since ADR 0022, product since taggable products) ---

    @Test // I6 — a Catalog enriches with exactly [view,update,delete,assign-tags]: catalogs are
    // taggable since ADR 0022 (the update handler's delta dispatch carries catalog:assign-tags), so
    // the verb joined the enrichment set. Pre-0022 this cell pinned its EXCLUSION.
    void catalogVerbSetIncludesAssignTags() throws Exception {
        var catalog = seedCatalog();
        ProgrammableOpaClient.perContextRule = ctx -> true;

        mockMvc.perform(get("/api/v1/catalogs/{id}", catalog.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._actions.view").value(true))
                .andExpect(jsonPath("$._actions.update").value(true))
                .andExpect(jsonPath("$._actions.delete").value(true))
                .andExpect(jsonPath("$._actions.['assign-tags']").value(true));
    }

    @Test // I6 — a Product enriches with exactly [view,update,delete,assign-tags]: products are
    // taggable now (the update handler's delta dispatch carries product:assign-tags), so the verb
    // joined the enrichment set. Pre-taggable-products this cell pinned its EXCLUSION.
    void productVerbSetIncludesAssignTags() throws Exception {
        var catalog = seedCatalog();
        var root = seedCategory(catalog.getId(), null, "root-cat", Map.of());
        var product = seedProduct(root.getId(), "widget");
        ProgrammableOpaClient.perContextRule = ctx -> true;

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{cat}/products/{p}",
                        catalog.getId(), root.getId(), product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._actions.view").value(true))
                .andExpect(jsonPath("$._actions.update").value(true))
                .andExpect(jsonPath("$._actions.delete").value(true))
                .andExpect(jsonPath("$._actions.['assign-tags']").value(true));
    }

    // --- I5: the codegen round-trip (the _actions wire key) ---------------------

    @Test // I5 — _actions round-trips through Jackson on the wire as the key "_actions"
    void actionsRoundTripsAsUnderscoreActionsKey() throws Exception {
        var catalog = seedCatalog();
        var emea = seedCategory(catalog.getId(), null, "emea-cat", Map.of("region", "emea"));
        ProgrammableOpaClient.perContextRule = ctx -> true;

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), emea.getId()))
                .andExpect(status().isOk())
                // the wire key is "_actions" (a Map<String,Boolean>), the resource fields coexist
                .andExpect(jsonPath("$._actions").isMap())
                .andExpect(jsonPath("$.name").value("emea-cat"))
                .andExpect(jsonPath("$.tags.region").value("emea"));
    }

    // --- seeding ----------------------------------------------------------------

    private CatalogEntity seedCatalog() {
        var entity = new CatalogEntity(UUID.randomUUID(), "enrich-it-catalog", null);
        hierarchy.assignPath(entity);
        return catalogs.save(entity);
    }

    private CategoryEntity seedCategory(UUID catalogId, UUID parentId, String name, Map<String, Object> tags) {
        var entity = new CategoryEntity(UUID.randomUUID(), catalogId, parentId, name, null);
        if (!tags.isEmpty()) {
            entity.setTags(ResourceTags.fromMap(tags));
        }
        hierarchy.assignPath(entity);
        return categories.save(entity);
    }

    private ProductEntity seedProduct(UUID categoryId, String name) {
        var entity = new ProductEntity(UUID.randomUUID(), categoryId, name, null, "SKU-1", 100L, "USD");
        hierarchy.assignPath(entity);
        return products.save(entity);
    }

    // --- the test doubles ---------------------------------------------------------

    @TestConfiguration
    static class EnrichmentTestConfig {

        @Bean
        AbacSubjectExtractor enrichmentSubjectExtractor() {
            AbacContext.Subject member = new AbacContext.Subject(
                    MEMBER.toString(), List.of("member"), Map.of("username", "enrich-member"));
            return request -> Optional.of(member);
        }

        @Bean
        OpaClient programmableOpaClient() {
            return new ProgrammableOpaClient();
        }

        @Bean
        RoleDefinitionSupplier enrichmentRoleSupplier() {
            // The stub decides from attributes/ancestors; an empty role is fine (no permissions needed).
            return (userId, type, id) -> Optional.empty();
        }
    }

    /**
     * Context-aware OPA stub deciding BOTH the gate's single {@code allow} and the enrichment
     * {@code allowAll} batch from each context's action + resolved attributes/ancestors.
     */
    static final class ProgrammableOpaClient implements OpaClient {
        static volatile Predicate<AbacContext> perContextRule = ctx -> true;
        static final List<AbacContext> captured = new ArrayList<>();

        @Override
        public synchronized boolean allow(AbacContext context) {
            captured.add(context);
            return perContextRule.test(context);
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.allowAll();
        }

        @Override
        public synchronized List<Boolean> allowAll(List<AbacContext> contexts) {
            List<Boolean> out = new ArrayList<>(contexts.size());
            for (AbacContext ctx : contexts) {
                captured.add(ctx);
                out.add(perContextRule.test(ctx));
            }
            return out;
        }
    }
}
