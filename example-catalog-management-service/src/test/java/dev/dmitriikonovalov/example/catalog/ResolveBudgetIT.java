package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.hierarchy.AncestorResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreeAncestorResolver;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreePathSource;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole-request resolve budget (Slice 7.3, ADR 0023 — QA case I3), real Postgres: one same-root
 * categories list request through the <strong>real</strong> gate (attribute-rich, role on the
 * governing parent), the <strong>real</strong> allowlist-batch finisher (the OPA stub's
 * {@code compile} answers {@code unsupported()} so the batch path runs, carrying per-row ancestor
 * chains), and the <strong>real</strong> enrichment advice (per-row {@code _actions}) — asserting
 * with counting fakes underneath the memo decorators:
 *
 * <ul>
 *   <li><strong>exactly one</strong> delegate-touching role resolve for the whole request (the
 *       measured 22-per-20-row-page fan-out, collapsed — every caller targets the identical
 *       governing root and the request memo replays it), and</li>
 *   <li><strong>at most one</strong> {@code ancestorsOf} per {@code (type, id)} (the query path's
 *       {@code withResource} and the advice's {@code prepareRow} used to resolve every row's chain
 *       twice).</li>
 * </ul>
 *
 * <p>This is the IT-level form of the amplification claim the k6 harness proves through the live
 * rig (PERFORMANCE.md §3) — pinned here forever at test level.
 */
@SpringBootTest(properties = {
    "catalog.role-source=none",
    "opa.abac.action-enrichment.enabled=true"
})
@Testcontainers
@AutoConfigureMockMvc
@Import(ResolveBudgetIT.ResolveBudgetTestConfig.class)
class ResolveBudgetIT {

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

    static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000b4d9e");

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;
    @Autowired CategoryRepository categories;
    @Autowired CatalogHierarchyService hierarchy;

    @BeforeEach
    void resetCounters() {
        CountingRoleSupplier.calls.clear();
        CountingAncestorResolver.chainCalls.clear();
    }

    @Test // I3 — the budget: 1 role resolve for the request; ≤1 ancestorsOf per (type,id)
    void sameRootListStaysWithinTheResolveBudget() throws Exception {
        CatalogEntity catalog = seedCatalog();
        seedCategory(catalog.getId(), "cat-a", Map.of("region", "emea"));
        seedCategory(catalog.getId(), "cat-b", Map.of("region", "emea"));
        seedCategory(catalog.getId(), "cat-c", Map.of("region", "apac"));
        resetCounters();

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories", catalog.getId())
                        .queryParam("perPage", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0]._actions.view").value(true))
                .andExpect(jsonPath("$.items[1]._actions.view").value(true))
                .andExpect(jsonPath("$.items[2]._actions.view").value(true));

        // The role budget: gate + list authorizer + finisher + 3 enriched rows all target the
        // identical governing root — ONE delegate call for the whole request (was: 2 + rows).
        assertThat(CountingRoleSupplier.calls)
                .containsOnlyKeys("%s|catalog|%s".formatted(MEMBER, catalog.getId()));
        assertThat(CountingRoleSupplier.calls.values()).singleElement().isEqualTo(1);

        // The ancestor budget: every (type,id) chain resolved at most once (the query path and the
        // advice used to resolve each row's chain twice — 2×N per page).
        assertThat(CountingAncestorResolver.chainCalls.values()).allMatch(count -> count == 1);
        assertThat(CountingAncestorResolver.chainCalls).containsKeys(
                "category|" + firstCategoryId(catalog.getId()));
    }

    private UUID firstCategoryId(UUID catalogId) {
        return categories.findAll().stream()
                .filter(c -> catalogId.equals(c.getCatalogId()))
                .map(CategoryEntity::getId)
                .findFirst()
                .orElseThrow();
    }

    private CatalogEntity seedCatalog() {
        var entity = new CatalogEntity(UUID.randomUUID(), "resolve-budget-catalog", null);
        hierarchy.assignPath(entity);
        return catalogs.save(entity);
    }

    private CategoryEntity seedCategory(UUID catalogId, String name, Map<String, Object> tags) {
        var entity = new CategoryEntity(UUID.randomUUID(), catalogId, null, name, null);
        if (!tags.isEmpty()) {
            entity.setTags(ResourceTags.fromMap(tags));
        }
        hierarchy.assignPath(entity);
        return categories.save(entity);
    }

    // --- the test doubles -------------------------------------------------------

    @TestConfiguration
    static class ResolveBudgetTestConfig {

        @Bean
        AbacSubjectExtractor budgetSubjectExtractor() {
            AbacContext.Subject member = new AbacContext.Subject(
                    MEMBER.toString(), List.of("member"), Map.of("username", "budget-member"));
            return request -> Optional.of(member);
        }

        @Bean
        OpaClient unsupportedResidualOpaClient() {
            return new AllowAllUnsupportedOpaClient();
        }

        /** The counting supplier — wrapped by the starter's memo BPP (default-on flag). */
        @Bean
        RoleDefinitionSupplier countingRoleSupplier() {
            return new CountingRoleSupplier();
        }

        /**
         * The counting ancestor resolver: the app's REAL ltree resolution underneath, delegate
         * calls counted per {@code (type,id)} — wrapped by the starter's memo BPP. Registering it
         * here makes the auto-config's own resolver back off ({@code @ConditionalOnMissingBean}).
         */
        @Bean
        AncestorResolver countingAncestorResolver(LtreePathSource pathSource) {
            return new CountingAncestorResolver(new LtreeAncestorResolver(pathSource, 32));
        }
    }

    /**
     * OPA stub: {@code compile} answers {@code unsupported()} so the list takes the REAL
     * allowlist-batch finisher (per-row contexts with ancestors — the query-path chain resolution);
     * {@code allow}/{@code allowAll} approve everything (the budget, not the cut, is under test).
     */
    static final class AllowAllUnsupportedOpaClient implements OpaClient {

        @Override
        public boolean allow(AbacContext context) {
            return true;
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.unsupported();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            List<Boolean> out = new ArrayList<>(contexts.size());
            contexts.forEach(ctx -> out.add(true));
            return out;
        }
    }

    static final class CountingRoleSupplier implements RoleDefinitionSupplier {

        static final ConcurrentMap<String, Integer> calls = new ConcurrentHashMap<>();

        @Override
        public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
            calls.merge(userId + "|" + resourceType + "|" + resourceId, 1, Integer::sum);
            return Optional.of(new RoleDefinition("budget-member-role", Map.of(), Map.of()));
        }
    }

    static final class CountingAncestorResolver implements AncestorResolver {

        static final ConcurrentMap<String, Integer> chainCalls = new ConcurrentHashMap<>();

        private final AncestorResolver delegate;

        CountingAncestorResolver(AncestorResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<dev.dmitriikonovalov.opaabac.core.ParentRef> ancestorsOf(String leafType, String leafId) {
            chainCalls.merge(leafType + "|" + leafId, 1, Integer::sum);
            return delegate.ancestorsOf(leafType, leafId);
        }

        @Override
        public <T> Specification<T> subtreeOf(String rootType, String rootId) {
            return delegate.subtreeOf(rootType, rootId);
        }
    }
}
