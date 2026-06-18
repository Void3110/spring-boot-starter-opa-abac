package dev.dmitriikonovalov.example.catalog;

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
 * List-path action-enrichment IT (Phase 6) — QA cases I1–I2, real Postgres.
 *
 * <p>Proves the T3 write-through feeds the advice: a {@code GET /categories} page write-throughs its
 * post-filter survivors into the cache, and the advice enriches each row by reading that snapshot —
 * with <strong>no second SELECT</strong> per row (the {@code @MockitoSpyBean} repository's
 * {@code findById} is never called by the advice). Each page element gets its own {@code _actions} map
 * reflecting its own resolved tags, computed by <strong>one</strong> bulk call for the page.
 */
@SpringBootTest(properties = {
    "catalog.role-source=none",
    "opa.abac.action-enrichment.enabled=true"
})
@Testcontainers
@AutoConfigureMockMvc
@Import(ActionEnrichmentListIT.ListEnrichmentTestConfig.class)
class ActionEnrichmentListIT {

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
    @MockitoSpyBean CategoryRepository categories; // a spy → I1 proves the advice never re-loaded a row
    @Autowired CatalogHierarchyService hierarchy;

    @BeforeEach
    void resetStub() {
        ProgrammableOpaClient.perContextRule = ctx -> true;
        ProgrammableOpaClient.captured.clear();
    }

    // --- I1: the no-second-SELECT write-through proof ---------------------------

    @Test // I1 — a list page: the advice enriches each row from the write-through cache, NOT a re-load
    void listEnrichesFromWriteThroughWithoutSecondSelect() throws Exception {
        var catalog = seedCatalog();
        seedCategory(catalog.getId(), null, "a-cat", Map.of("region", "emea"));
        seedCategory(catalog.getId(), null, "b-cat", Map.of("region", "emea"));
        ProgrammableOpaClient.perContextRule = ctx -> true;
        org.mockito.Mockito.clearInvocations(categories);

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories", catalog.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0]._actions.view").value(true))
                .andExpect(jsonPath("$.items[1]._actions.view").value(true));

        // The advice reads each row from the write-through cache — it never re-loads a single row by id.
        org.mockito.Mockito.verify(categories, org.mockito.Mockito.never())
                .findById(org.mockito.ArgumentMatchers.any());
    }

    // --- I2: per-row maps reflect each row's own resolved tags ------------------

    @Test // I2 — mixed-tag rows on one page: each items[i]._actions mirrors that row's tags
    void perRowActionsReflectEachRowsTags() throws Exception {
        var catalog = seedCatalog();
        seedCategory(catalog.getId(), null, "emea-cat", Map.of("region", "emea"));
        seedCategory(catalog.getId(), null, "apac-cat", Map.of("region", "apac"));
        // the coarse list gate + view always allowed; update allowed only for emea rows
        ProgrammableOpaClient.perContextRule = ctx -> ctx.action().endsWith(":list")
                || ctx.action().endsWith(":view")
                || "emea".equals(ctx.resource().attributes().get("region"));

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories", catalog.getId())
                        .queryParam("perPage", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                // every row has a complete map (view present on both); update differs per resolved tag
                .andExpect(jsonPath("$.items[?(@.name=='emea-cat')]._actions.update").value(true))
                .andExpect(jsonPath("$.items[?(@.name=='apac-cat')]._actions.update").value(false))
                .andExpect(jsonPath("$.items[?(@.name=='emea-cat')]._actions.view").value(true))
                .andExpect(jsonPath("$.items[?(@.name=='apac-cat')]._actions.view").value(true));
    }

    // --- seeding ----------------------------------------------------------------

    private CatalogEntity seedCatalog() {
        var entity = new CatalogEntity(UUID.randomUUID(), "list-enrich-catalog", null);
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

    // --- the test doubles ---------------------------------------------------------

    @TestConfiguration
    static class ListEnrichmentTestConfig {

        @Bean
        AbacSubjectExtractor listSubjectExtractor() {
            AbacContext.Subject member = new AbacContext.Subject(
                    MEMBER.toString(), List.of("member"), Map.of("username", "list-member"));
            return request -> Optional.of(member);
        }

        @Bean
        OpaClient programmableOpaClient() {
            return new ProgrammableOpaClient();
        }

        @Bean
        RoleDefinitionSupplier listRoleSupplier() {
            return (userId, type, id) -> Optional.empty();
        }
    }

    /**
     * Context-aware OPA stub. {@code compile} returns an unsupported residual so the list takes the
     * allowlist-batch path (the survivors are then write-through-cached); {@code allowAll} doubles as the
     * list's per-row allowlist <em>and</em> the enrichment per-verb batch, both decided per context.
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
            // Pure-SQL path: an ALLOW_ALL residual so the page is materialized then write-through-cached,
            // and the advice's enrichment is the only allowAll consumer of resolved attributes.
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
