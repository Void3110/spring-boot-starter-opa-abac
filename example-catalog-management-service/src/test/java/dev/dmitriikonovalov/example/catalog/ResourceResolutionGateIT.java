package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The gate-semantics IT for resource resolution (Phase 5.97) — QA cases I1–I7, real Postgres.
 *
 * <p>Unlike the CRUD suites (which run on the kill-switch off-state), resolution is <strong>ON</strong>
 * here (the default), and the OPA stub is <em>context-aware</em>: it decides from
 * {@code input.resource.attributes} / {@code ancestors} — proving the gate decided on the
 * <strong>resolved</strong> state it could never see before, not on a bare reference. Two
 * deterministic race hooks (test aspects ordered after the order-190 method-security interceptor)
 * pin the version-binding contract: a bump in the gate→handler window → the {@code VersionGuard} 409
 * (I4), and a bump after the guard, inside the save, → the dao advice's 409 (I5) — the audited
 * 500-class, closed.
 */
// Action enrichment is turned OFF here: this suite pins the GATE's resolution semantics in isolation
// (e.g. the governing-root role is looked up exactly once, by the gate). With enrichment on, a GET also
// runs the read-side advice, which independently resolves the governing-root role per enriched row —
// correct, but it would add a second lookup and couple this gate suite to enrichment. Enrichment has its
// own end-to-end coverage in ActionEnrichmentIT / ActionEnrichmentListIT.
@SpringBootTest(properties = {
    "catalog.role-source=none",
    "opa.abac.action-enrichment.enabled=false"
})
@Testcontainers
@AutoConfigureMockMvc
@Import(ResourceResolutionGateIT.GateTestConfig.class)
class ResourceResolutionGateIT {

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

    /** Allow a check iff the RESOLVED attributes carry {@code region=emea} — undecidable pre-5.97. */
    static final Predicate<AbacContext> EMEA_ONLY =
            ctx -> "emea".equals(ctx.resource().attributes().get("region"));

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;
    @MockitoSpyBean CategoryRepository categories; // a spy, so I3 can prove the handler reused the snapshot
    @Autowired ProductRepository products;
    @Autowired CatalogHierarchyService hierarchy;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetStubs() {
        ProgrammableOpaClient.rule = ctx -> false; // fail-closed default; each case sets its rule
        ProgrammableOpaClient.captured.clear();
        RecordingRoleSupplier.lookups.clear();
        RaceInjector.BEFORE_SAVE.set(null);
        RaceInjector.BEFORE_MUTATE.set(null);
    }

    // --- I1/I2: the tag cells, decided AT THE GATE -----------------------------

    @Test // I1 — tag-match write allowed: the gate saw the resolved tags and granted
    void tagMatchWriteAllowedAtGate() throws Exception {
        var catalog = seedCatalog();
        var emea = seedCategory(catalog.getId(), null, "emea-cat", Map.of("region", "emea"));
        ProgrammableOpaClient.rule = EMEA_ONLY;

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), emea.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"));

        assertThat(categories.findById(emea.getId()).orElseThrow().getName()).isEqualTo("renamed");
        // the decision input carried the RESOLVED attributes (not the empty reference map)
        AbacContext decided = ProgrammableOpaClient.captured.get(0);
        assertThat(decided.resource().attributes()).containsEntry("region", "emea");
    }

    @Test // I2 — tag-mismatch write denied at the gate: the handler never ran
    void tagMismatchWriteDeniedAtGate() throws Exception {
        var catalog = seedCatalog();
        var apac = seedCategory(catalog.getId(), null, "apac-cat", Map.of("region", "apac"));
        Integer versionBefore = categories.findById(apac.getId()).orElseThrow().getVersion();
        ProgrammableOpaClient.rule = EMEA_ONLY;

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), apac.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hacked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        var row = categories.findById(apac.getId()).orElseThrow();
        assertThat(row.getName()).isEqualTo("apac-cat"); // byte-identical row —
        assertThat(row.getVersion()).isEqualTo(versionBefore); // no version bump: the handler never ran
    }

    // --- I3: getCategory parity (its first-ever annotation) --------------------

    @Test // I3 — 200 via the gate; the handler serves the authorized snapshot (no second load)
    void getCategoryServesTheAuthorizedSnapshot() throws Exception {
        var catalog = seedCatalog();
        var emea = seedCategory(catalog.getId(), null, "emea-cat", Map.of("region", "emea"));
        ProgrammableOpaClient.rule = ctx -> true;
        org.mockito.Mockito.clearInvocations(categories);

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), emea.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(emea.getId().toString()))
                .andExpect(jsonPath("$.name").value("emea-cat"))
                .andExpect(jsonPath("$.tags.region").value("emea"));

        // exactly one load — the resolver's; the handler took the cache path, never the repository
        org.mockito.Mockito.verify(categories, org.mockito.Mockito.times(1)).findById(emea.getId());
        org.mockito.Mockito.verify(categories, org.mockito.Mockito.never())
                .findByIdAndCatalogId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test // I3 — an EXISTING category under the wrong catalog path stays the handler's 404
    void getCategoryWrongCatalogScopeIs404() throws Exception {
        var catalog = seedCatalog();
        var other = seedCatalog();
        var emea = seedCategory(catalog.getId(), null, "emea-cat", Map.of("region", "emea"));
        ProgrammableOpaClient.rule = ctx -> true;

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{id}", other.getId(), emea.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    // --- I4: the deterministic version-guard race ------------------------------

    @Test // I4 — an out-of-band bump between gate and write → 409, the mutation does NOT apply.
    // Phase 6.5 moved this cell from the category PUT to the catalog PUT; ADR 0022 moved it AGAIN,
    // to the PRODUCT PUT (taggable catalogs made the catalog update dispatch in-handler). Taggable
    // PRODUCTS then made the product PUT dispatch too — but unlike the earlier moves, this cell
    // SURVIVES the change in place: the dispatched TagDecisionGate decisions still resolve the
    // instance into the request cache (annotated resourceId, 5.97 write-through), and the product
    // handler alone runs its version guard INSIDE mutate()'s locked transaction — so the
    // gate→write window this cell pins still exists here, and only here. The race fires between
    // the handler's dispatch and mutate()'s locked load: the gate's snapshot no longer matches
    // the row the write would touch.
    void gateWindowRaceAnswers409AndMutationDoesNotApply() throws Exception {
        var catalog = seedCatalog();
        var root = seedCategory(catalog.getId(), null, "root-cat", Map.of());
        var product = seedProduct(root.getId(), "widget");
        ProgrammableOpaClient.rule = ctx -> true;
        RaceInjector.BEFORE_MUTATE.set(() -> jdbc.update(
                "update product set version = version + 1, name = 'raced' where id = ?", product.getId()));

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{cat}/products/{p}",
                        catalog.getId(), root.getId(), product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mine\",\"sku\":\"SKU-1\",\"priceCents\":100,\"currency\":\"USD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STATE_CONFLICT"));

        // The caller's write did NOT apply. (Unlike the old catalog-window cell, the race hook here
        // fires INSIDE mutate()'s transaction — the unordered aspect is innermost — so the racer's
        // own bump rolls back with the aborted write and the row reverts to its seeded state; the
        // durable pin is that "mine" never landed, not which writer's state survived.)
        var row = products.findById(product.getId()).orElseThrow();
        assertThat(row.getName()).isEqualTo("widget");
    }

    // --- I5: the dao advice, live (the audited 500-class) -----------------------

    @Test // I5 — a stale-@Version save (bump AFTER the guard, inside the save) → 409, not 500
    void staleVersionSaveAnswers409NotF500() throws Exception {
        var catalog = seedCatalog();
        ProgrammableOpaClient.rule = ctx -> true;
        RaceInjector.BEFORE_SAVE.set(() -> jdbc.update(
                "update catalog set version = version + 1 where id = ?", catalog.getId()));

        mockMvc.perform(put("/api/v1/catalogs/{id}", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STATE_CONFLICT"));
    }

    // --- I6: missing id behind an annotated resourceId → 403 (pinned semantic #1)

    @Test // I6 — resolution on: a nonexistent id behind an ANNOTATED resourceId answers 403 at the
    // gate, with NO OPA call. Phase 6.5: the category PUT no longer carries an annotated resourceId
    // (its decisions are delta-dispatched in-handler AFTER the load), so its missing-id answer is the
    // handler's 404 — the 403 pin holds exactly where the annotated precondition still exists (GET).
    void missingIdAnswers403WithoutOpaCall() throws Exception {
        var catalog = seedCatalog();
        ProgrammableOpaClient.rule = ctx -> true; // even an allow-all policy cannot reach this

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));

        assertThat(ProgrammableOpaClient.captured)
                .as("instance failure denies WITHOUT an OPA call — never an attribute-less context")
                .isEmpty();
        // (the off-state 404 half of I6 is pinned by the CRUD/error-contract suites, which run with
        // opa.abac.resource-resolution.enabled=false — the kill-switch baseline proof)
    }

    // --- I7: ancestors at the gate + the governing-root role lookup -------------

    @Test // I7 — a nested category: the chain reaches the gate root-first; the role is looked up
    // ONCE, on the governing root (the catalog)
    void nestedCategoryDecidedWithAncestorsAndRootRole() throws Exception {
        var catalog = seedCatalog();
        var root = seedCategory(catalog.getId(), null, "root-cat", Map.of());
        var child = seedCategory(catalog.getId(), root.getId(), "child-cat", Map.of("region", "emea"));
        ProgrammableOpaClient.rule = ctx -> true;

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), child.getId()))
                .andExpect(status().isOk());

        AbacContext decided = ProgrammableOpaClient.captured.get(0);
        assertThat(decided.resource().ancestors())
                .extracting(p -> p.type() + ":" + p.id())
                .containsExactly( // root-first, leaf-excluded
                        "catalog:" + catalog.getId(),
                        "category:" + root.getId());
        assertThat(decided.resource().attributes()).containsEntry("region", "emea");
        assertThat(RecordingRoleSupplier.lookups).hasSize(1);
        assertThat(RecordingRoleSupplier.lookups.get(0))
                .isEqualTo(new RecordingRoleSupplier.Lookup(
                        MEMBER.toString(), "catalog", catalog.getId().toString()));
    }

    @Test // I7 — a product two levels deep: full chain, role still on the catalog root
    void productDecidedWithFullChainAndRootRole() throws Exception {
        var catalog = seedCatalog();
        var root = seedCategory(catalog.getId(), null, "root-cat", Map.of());
        var product = seedProduct(root.getId(), "widget");
        ProgrammableOpaClient.rule = ctx -> true;

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{cat}/products/{p}",
                        catalog.getId(), root.getId(), product.getId()))
                .andExpect(status().isOk());

        AbacContext decided = ProgrammableOpaClient.captured.get(0);
        assertThat(decided.resource().ancestors())
                .extracting(p -> p.type() + ":" + p.id())
                .containsExactly(
                        "catalog:" + catalog.getId(),
                        "category:" + root.getId());
        assertThat(RecordingRoleSupplier.lookups.get(0).type()).isEqualTo("catalog");
    }

    // --- I8: the update-vs-delete race → the starter's 404 advice, live -------------------------

    @Test // I8 — the row vanishes between the handler's scope check and mutate()'s locked load →
    // the LIBRARY EntityNotFoundException → the starter advice's 404 problem+json, not a 500
    void updateVsDeleteRaceAnswers404NotF500() throws Exception {
        var catalog = seedCatalog();
        var root = seedCategory(catalog.getId(), null, "root-cat", Map.of());
        var product = seedProduct(root.getId(), "vanishing");
        ProgrammableOpaClient.rule = ctx -> true;
        RaceInjector.BEFORE_MUTATE.set(() -> jdbc.update(
                "delete from product where id = ?", product.getId()));

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{cat}/products/{p}",
                        catalog.getId(), root.getId(), product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"too late\",\"sku\":\"SKU-1\",\"priceCents\":100,\"currency\":\"USD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    // --- seeding ----------------------------------------------------------------

    private CatalogEntity seedCatalog() {
        var entity = new CatalogEntity(UUID.randomUUID(), "gate-it-catalog", null);
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
    static class GateTestConfig {

        @Bean
        AbacSubjectExtractor gateSubjectExtractor() {
            AbacContext.Subject member = new AbacContext.Subject(
                    MEMBER.toString(), List.of("member"), Map.of("username", "gate-member"));
            return request -> Optional.of(member);
        }

        @Bean
        OpaClient programmableOpaClient() {
            return new ProgrammableOpaClient();
        }

        @Bean
        RoleDefinitionSupplier recordingRoleSupplier() {
            return new RecordingRoleSupplier();
        }

        @Bean
        RaceInjector raceInjector() {
            return new RaceInjector();
        }
    }

    /** Context-aware OPA stub: decides from the resolved resource the gate sent; captures every input. */
    static final class ProgrammableOpaClient implements OpaClient {
        static volatile Predicate<AbacContext> rule = ctx -> false;
        static final List<AbacContext> captured = new CopyOnWriteArrayList<>();

        @Override
        public boolean allow(AbacContext context) {
            captured.add(context);
            return rule.test(context);
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.allowAll(); // list paths are not under test here
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return Collections.nCopies(contexts.size(), Boolean.TRUE);
        }
    }

    /** Records every role lookup so the governing-root rule is observable. */
    static final class RecordingRoleSupplier implements RoleDefinitionSupplier {
        record Lookup(String userId, String type, String id) {}

        static final List<Lookup> lookups = new CopyOnWriteArrayList<>();

        @Override
        public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
            lookups.add(new Lookup(userId, resourceType, resourceId));
            return Optional.empty(); // the programmable stub decides; no role definition needed
        }
    }

    /**
     * Deterministic race injection. Each hook fires at most once (get-and-clear). {@code BEFORE_SAVE}
     * fires immediately before the repository save — after the handler's load and guard (the I5
     * window the {@code @Version} column owns). {@code BEFORE_MUTATE} fires between the product
     * handler's scope checks and mutate()'s locked load (the I4 gate-window and I8 update-vs-delete
     * cells; the product PUT is the last statically-annotated mutation — catalog and category
     * updates delta-dispatch in-handler since 6.5/ADR 0022 and no longer have the window).
     */
    @Aspect
    static class RaceInjector {
        static final AtomicReference<Runnable> BEFORE_SAVE = new AtomicReference<>();
        static final AtomicReference<Runnable> BEFORE_MUTATE = new AtomicReference<>();

        @Before("this(dev.dmitriikonovalov.example.catalog.domain.CatalogRepository) && execution(* save(..))")
        void beforeSave() {
            Runnable race = BEFORE_SAVE.getAndSet(null);
            if (race != null) {
                race.run();
            }
        }

        // The I8 window: after the handler's scope check, before mutate()'s locked load — the
        // update-vs-delete race the starter's EntityNotFoundProblemAdvice owns. mutate() is DECLARED
        // on the library base class, so the execution pattern must name it (a subclass pattern does
        // not match inherited methods); target() scopes the hook to the product service.
        @Before("execution(* dev.dmitriikonovalov.opaabac.data.service.AbstractCrudService.mutate(..))"
                + " && target(dev.dmitriikonovalov.example.catalog.domain.ProductService)")
        void beforeMutate() {
            Runnable race = BEFORE_MUTATE.getAndSet(null);
            if (race != null) {
                race.run();
            }
        }
    }
}
