package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.ResolveTarget;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.data.filter.GovernedScopeResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolutionException;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreeAncestorResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreePathSource;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The advice's batch pass on a <strong>multi-root</strong> page (Slice 7.3, ADR 0024 §5 — QA case
 * I2), real Postgres: a catalogs list where every row is its own governing root (the page shape a
 * duplicate-target memo cannot help). Asserts, with a counting supplier under the memo decorator:
 * <ul>
 *   <li>one 20-row page issues exactly <strong>one</strong> {@code lookupAll} and zero per-row
 *       single lookups from the advice — the page's whole wire shape is <em>1 single + 1 batch</em>
 *       (the authorizer's query-time coarse role on the first governed root, memo-held and so
 *       excluded from the batch as a hit, + one batch for the remaining distinct roots);</li>
 *   <li>one row's <em>ancestor</em> failure omits <strong>that row only</strong> — the others stay
 *       enriched (the per-row rung is intact);</li>
 *   <li>a <em>batch outage</em> ({@code lookupAll} throwing) omits <strong>all</strong>
 *       {@code _actions} while the response stays {@code 200} with full rows — affordance never
 *       blocks (the whole-group rung).</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "catalog.role-source=none",
    "opa.abac.action-enrichment.enabled=true"
})
@Testcontainers
@AutoConfigureMockMvc
@Import(MultiRootEnrichmentIT.MultiRootTestConfig.class)
class MultiRootEnrichmentIT {

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

    static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000707071");
    static final int ROWS = 20;
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;
    @Autowired CatalogHierarchyService hierarchy;

    @BeforeEach
    void reset() {
        CountingBatchSupplier.batches.clear();
        CountingBatchSupplier.singleLookups.clear();
        CountingBatchSupplier.failBatch.set(false);
        SelectivelyFailingAncestorResolver.failForId.set(null);
        ScopeStub.governed.clear();
    }

    private List<UUID> seedPage() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            var entity = new CatalogEntity(UUID.randomUUID(), "multi-root-" + i, null);
            hierarchy.assignPath(entity);
            ids.add(catalogs.save(entity).getId());
        }
        ScopeStub.governed.put(MEMBER.toString(), List.copyOf(ids));
        return ids;
    }

    @Test // I2 — the multi-root page costs ONE lookupAll (all rows' distinct roots in one batch)
    void multiRootPageIssuesExactlyOneBatch() throws Exception {
        List<UUID> ids = seedPage();

        MvcResult result = mockMvc.perform(get("/api/v1/catalogs").queryParam("perPage", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(ROWS))
                .andReturn();

        JsonNode items = MAPPER.readTree(result.getResponse().getContentAsString()).get("items");
        for (JsonNode item : items) {
            assertThat(item.has("_actions")).as("every row enriched: %s", item).isTrue();
            assertThat(item.get("_actions").get("view").asBoolean()).isTrue();
        }

        assertThat(CountingBatchSupplier.batches)
                .as("exactly one batch role resolution for the whole page")
                .hasSize(1);
        // The list authorizer resolved the FIRST governed root at query time (the coarse
        // filter-residual role) — the memo holds it, so the advice's batch correctly excludes it
        // as a hit and delegates only the misses: the page's wire shape is 1 single + 1 batch.
        assertThat(CountingBatchSupplier.batches.get(0))
                .hasSize(ROWS - 1)
                .doesNotContain(new ResolveTarget("catalog", ids.get(0).toString()))
                .containsAll(ids.subList(1, ROWS).stream()
                        .map(id -> new ResolveTarget("catalog", id.toString()))
                        .toList());
        assertThat(CountingBatchSupplier.singleLookups)
                .as("one query-time single: the authorizer's coarse role on the first governed root")
                .containsExactly(MEMBER + "|catalog|" + ids.get(0));
    }

    @Test // I2 — one row's ancestor failure omits THAT row only; the batch carries the rest
    void ancestorFailureOmitsOnlyThatRow() throws Exception {
        List<UUID> ids = seedPage();
        UUID failing = ids.get(3);
        SelectivelyFailingAncestorResolver.failForId.set(failing.toString());

        MvcResult result = mockMvc.perform(get("/api/v1/catalogs").queryParam("perPage", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(ROWS))
                .andReturn();

        JsonNode items = MAPPER.readTree(result.getResponse().getContentAsString()).get("items");
        int enriched = 0;
        for (JsonNode item : items) {
            if (failing.toString().equals(item.get("id").asText())) {
                assertThat(item.has("_actions")).as("the failed row is omitted").isFalse();
            } else {
                assertThat(item.has("_actions")).as("other rows stay enriched").isTrue();
                enriched++;
            }
        }
        assertThat(enriched).isEqualTo(ROWS - 1);
        assertThat(CountingBatchSupplier.batches).hasSize(1);
        assertThat(CountingBatchSupplier.batches.get(0))
                .as("the failed row never made it into the batch (and the first root is a memo hit)")
                .hasSize(ROWS - 2)
                .doesNotContain(new ResolveTarget("catalog", failing.toString()));
    }

    @Test // I2 — a batch outage omits ALL _actions; the response stays 200 with full rows
    void batchOutageOmitsAllActionsButNeverBlocks() throws Exception {
        seedPage();
        CountingBatchSupplier.failBatch.set(true);

        MvcResult result = mockMvc.perform(get("/api/v1/catalogs").queryParam("perPage", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(ROWS))
                .andReturn();

        JsonNode items = MAPPER.readTree(result.getResponse().getContentAsString()).get("items");
        for (JsonNode item : items) {
            assertThat(item.has("_actions"))
                    .as("whole-batch outage → the whole group's _actions omitted")
                    .isFalse();
        }
    }

    // --- the test doubles -------------------------------------------------------

    @TestConfiguration
    static class MultiRootTestConfig {

        @Bean
        AbacSubjectExtractor multiRootSubjectExtractor() {
            AbacContext.Subject member = new AbacContext.Subject(
                    MEMBER.toString(), List.of("member"), Map.of("username", "multi-root-member"));
            return request -> Optional.of(member);
        }

        @Bean
        OpaClient allowAllOpaClient() {
            return new AllowAllOpaClient();
        }

        /** The counting supplier — wrapped by the memo BPP; the advice's batch goes through it. */
        @Bean
        RoleDefinitionSupplier countingBatchSupplier() {
            return new CountingBatchSupplier();
        }

        /** The membership cut: per-subject governed catalog ids (the CatalogListIsolationIT idiom). */
        @Bean
        GovernedScopeResolver scopeStub() {
            return new ScopeStub();
        }

        /** Real ltree ancestors underneath, one designated id made to fail (the per-row rung). */
        @Bean
        AncestorResolver selectivelyFailingAncestorResolver(LtreePathSource pathSource) {
            return new SelectivelyFailingAncestorResolver(new LtreeAncestorResolver(pathSource, 32));
        }
    }

    static final class AllowAllOpaClient implements OpaClient {
        @Override
        public boolean allow(AbacContext context) {
            return true;
        }

        @Override
        public PartialResult compile(AbacContext context) {
            // Pure-SQL residual: the page is materialized then write-through-cached; the advice's
            // enrichment is the only per-row consumer (the ActionEnrichmentListIT idiom).
            return PartialResult.allowAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            List<Boolean> out = new ArrayList<>(contexts.size());
            contexts.forEach(ctx -> out.add(true));
            return out;
        }
    }

    static final class CountingBatchSupplier implements RoleDefinitionSupplier {

        static final List<Set<ResolveTarget>> batches = new CopyOnWriteArrayList<>();
        static final List<String> singleLookups = new CopyOnWriteArrayList<>();
        static final AtomicBoolean failBatch = new AtomicBoolean(false);

        @Override
        public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
            singleLookups.add(userId + "|" + resourceType + "|"
                    + (resourceId == null ? "<type-level>" : resourceId));
            return Optional.of(role());
        }

        @Override
        public Map<ResolveTarget, Optional<RoleDefinition>> lookupAll(
                String userId, Set<ResolveTarget> targets) {
            batches.add(targets);
            if (failBatch.get()) {
                throw new RoleResolutionException("role source down (whole batch)");
            }
            Map<ResolveTarget, Optional<RoleDefinition>> out = new java.util.HashMap<>();
            targets.forEach(t -> out.put(t, Optional.of(role())));
            return out;
        }

        private static RoleDefinition role() {
            return new RoleDefinition("multi-root-member", Map.of(),
                    Map.of("catalog", List.of("READ")));
        }
    }

    /** Per-subject governed ids (the CatalogListIsolationIT idiom). */
    static final class ScopeStub implements GovernedScopeResolver {

        static final Map<String, List<UUID>> governed = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public List<UUID> governedIds(String subject, String resourceType) {
            return governed.getOrDefault(subject, List.of());
        }
    }

    static final class SelectivelyFailingAncestorResolver implements AncestorResolver {

        static final AtomicReference<String> failForId = new AtomicReference<>();

        private final AncestorResolver delegate;

        SelectivelyFailingAncestorResolver(AncestorResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<dev.dmitriikonovalov.opaabac.core.ParentRef> ancestorsOf(String leafType, String leafId) {
            if (leafId.equals(failForId.get())) {
                throw new AncestorResolutionException("broken lineage (test)");
            }
            return delegate.ancestorsOf(leafType, leafId);
        }

        @Override
        public <T> Specification<T> subtreeOf(String rootType, String rootId) {
            return delegate.subtreeOf(rootType, rootId);
        }
    }
}
