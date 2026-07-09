package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionClient;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionView;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The delta-aware gate-dispatch IT (Phase 6.5 — QA I13–I16, real Postgres): the OPA stub decides
 * <b>per fine action</b> and records the <b>decision sequence</b>, proving which questions the
 * category update/create handlers asked — the TAG/WRITE boundary that a static annotation could
 * never express — and that a denied decision precedes any mutation.
 */
@SpringBootTest(properties = "catalog.role-source=none")
@Testcontainers
@AutoConfigureMockMvc
@Import(TagDecisionGateIT.DispatchTestConfig.class)
class TagDecisionGateIT {

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

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;
    @Autowired CategoryRepository categories;
    @Autowired CatalogHierarchyService hierarchy;

    @BeforeEach
    void resetStub() {
        ActionAwareOpaClient.rule = action -> false; // fail-closed default; each case sets its rule
        ActionAwareOpaClient.askedActions.clear();
    }

    private static Predicate<String> allowOnly(String... actions) {
        var allowed = List.of(actions);
        return allowed::contains;
    }

    // --- I13: a tags-delta-only PUT asks exactly [assign-tags] ----------------------

    @Test
    void tagsDeltaOnlyAsksAssignTagsAlone() throws Exception {
        var catalog = seedCatalog();
        var cat = seedCategory(catalog.getId(), "kept-name", Map.of("region", "emea"));
        ActionAwareOpaClient.rule = allowOnly("category:assign-tags"); // update would DENY

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), cat.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"kept-name\",\"tags\":{\"region\":[\"emea\",\"amer\"]}}"))
                .andExpect(status().isOk());

        assertThat(ActionAwareOpaClient.askedActions).containsExactly("category:assign-tags");
        assertThat(categories.findById(cat.getId()).orElseThrow().getTags().asMap())
                .containsEntry("region", List.of("emea", "amer"));
    }

    // --- I14: content-delta-only asks [update]; both deltas ask both, in order ---------

    @Test
    void contentDeltaOnlyAsksUpdateAlone() throws Exception {
        var catalog = seedCatalog();
        var cat = seedCategory(catalog.getId(), "old-name", Map.of());
        ActionAwareOpaClient.rule = allowOnly("category:update");

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), cat.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"new-name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("new-name"));

        assertThat(ActionAwareOpaClient.askedActions).containsExactly("category:update");
    }

    @Test
    void bothDeltasAskBothDecisionsInOrder() throws Exception {
        var catalog = seedCatalog();
        var cat = seedCategory(catalog.getId(), "old-name", Map.of());
        ActionAwareOpaClient.rule = allowOnly("category:update", "category:assign-tags");

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), cat.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"new-name\",\"tags\":{\"region\":[\"emea\"]}}"))
                .andExpect(status().isOk());

        assertThat(ActionAwareOpaClient.askedActions)
                .containsExactly("category:update", "category:assign-tags");
    }

    // --- I15: a denied assign-tags (WRITE-no-TAG) leaves the row untouched ----------------

    @Test
    void deniedAssignTagsLeavesTheEntityUntouched() throws Exception {
        var catalog = seedCatalog();
        var cat = seedCategory(catalog.getId(), "kept-name", Map.of("region", "emea"));
        Integer versionBefore = categories.findById(cat.getId()).orElseThrow().getVersion();
        ActionAwareOpaClient.rule = allowOnly("category:update"); // TAG is what's missing

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), cat.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"kept-name\",\"tags\":{\"region\":[\"apac\"]}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        var row = categories.findById(cat.getId()).orElseThrow();
        assertThat(row.getName()).isEqualTo("kept-name");
        assertThat(row.getTags().asMap()).containsEntry("region", "emea");
        assertThat(row.getVersion()).isEqualTo(versionBefore); // the deny preceded ANY mutation
    }

    @Test // the boundary's other direction: a TAG-only holder EDITING CONTENT is denied via update
    void deniedUpdateBlocksContentEdit() throws Exception {
        var catalog = seedCatalog();
        var cat = seedCategory(catalog.getId(), "kept-name", Map.of());
        Integer versionBefore = categories.findById(cat.getId()).orElseThrow().getVersion();
        ActionAwareOpaClient.rule = allowOnly("category:assign-tags"); // WRITE is what's missing

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), cat.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"edited\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        assertThat(ActionAwareOpaClient.askedActions).containsExactly("category:update");
        var row = categories.findById(cat.getId()).orElseThrow();
        assertThat(row.getName()).isEqualTo("kept-name");
        assertThat(row.getVersion()).isEqualTo(versionBefore);
    }

    // --- I16: tag-on-create, the bare create, the empty delta, and the read/delete verbs ----

    @Test
    void createWithTagsDeniedAtTypeLevelPersistsNothing() throws Exception {
        var catalog = seedCatalog();
        long before = categories.count();
        ActionAwareOpaClient.rule = allowOnly("category:create"); // assign-tags DENIED

        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tagged\",\"tags\":{\"region\":[\"emea\"]}}"))
                .andExpect(status().isForbidden());

        assertThat(ActionAwareOpaClient.askedActions)
                .containsExactly("category:create", "category:assign-tags");
        assertThat(categories.count()).isEqualTo(before); // nothing persisted
    }

    @Test
    void createWithoutTagsAsksCreateAlone() throws Exception {
        var catalog = seedCatalog();
        ActionAwareOpaClient.rule = allowOnly("category:create");

        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"plain\"}"))
                .andExpect(status().isCreated());

        assertThat(ActionAwareOpaClient.askedActions).containsExactly("category:create");
    }

    @Test // the conservative default: an empty-delta PUT still asks update
    void emptyDeltaPutAsksUpdate() throws Exception {
        var catalog = seedCatalog();
        var cat = seedCategory(catalog.getId(), "same-name", Map.of());
        ActionAwareOpaClient.rule = allowOnly("category:update");

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), cat.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"same-name\"}"))
                .andExpect(status().isOk());

        assertThat(ActionAwareOpaClient.askedActions).containsExactly("category:update");
    }

    @Test // GET-one / GET-list / DELETE ask view / list / delete — the sweep, observed live
    void readAndDeleteVerbsAreTheSweptOnes() throws Exception {
        var catalog = seedCatalog();
        var cat = seedCategory(catalog.getId(), "viewed", Map.of());

        ActionAwareOpaClient.rule = allowOnly("category:view");
        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), cat.getId()))
                .andExpect(status().isOk());
        assertThat(ActionAwareOpaClient.askedActions).containsExactly("category:view");

        ActionAwareOpaClient.askedActions.clear();
        ActionAwareOpaClient.rule = allowOnly("category:list");
        mockMvc.perform(get("/api/v1/catalogs/{c}/categories", catalog.getId()))
                .andExpect(status().isOk());
        assertThat(ActionAwareOpaClient.askedActions).containsExactly("category:list");

        ActionAwareOpaClient.askedActions.clear();
        ActionAwareOpaClient.rule = allowOnly("category:delete");
        mockMvc.perform(delete("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), cat.getId()))
                .andExpect(status().isNoContent());
        assertThat(ActionAwareOpaClient.askedActions).containsExactly("category:delete");
    }

    // --- the CATALOG mirror (ADR 0022 — taggable catalogs adopt the same dispatch) ----------------
    // Deep-review finding (2026-07-10): the catalog PUT gained the identical delta dispatch but every
    // dispatch cell above targets the category endpoint — these cells pin the catalog handler.

    @Test // I13c — a tags-delta-only catalog PUT asks exactly [catalog:assign-tags]
    void catalogTagsDeltaOnlyAsksAssignTagsAlone() throws Exception {
        var catalog = seedCatalog(Map.of("region", List.of("emea")));
        ActionAwareOpaClient.rule = allowOnly("catalog:assign-tags"); // update would DENY

        mockMvc.perform(put("/api/v1/catalogs/{id}", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"dispatch-it-catalog\",\"tags\":{\"region\":[\"emea\",\"amer\"]}}"))
                .andExpect(status().isOk());

        assertThat(ActionAwareOpaClient.askedActions).containsExactly("catalog:assign-tags");
        assertThat(catalogs.findById(catalog.getId()).orElseThrow().getTags().asMap())
                .containsEntry("region", List.of("emea", "amer"));
    }

    @Test // I14c — content-delta-only asks [catalog:update]; both deltas ask both, in order
    void catalogBothDeltasAskBothDecisionsInOrder() throws Exception {
        var catalog = seedCatalog(Map.of());
        ActionAwareOpaClient.rule = allowOnly("catalog:update");

        mockMvc.perform(put("/api/v1/catalogs/{id}", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed\"}"))
                .andExpect(status().isOk());
        assertThat(ActionAwareOpaClient.askedActions).containsExactly("catalog:update");

        ActionAwareOpaClient.askedActions.clear();
        ActionAwareOpaClient.rule = allowOnly("catalog:update", "catalog:assign-tags");
        mockMvc.perform(put("/api/v1/catalogs/{id}", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed-again\",\"tags\":{\"region\":[\"emea\"]}}"))
                .andExpect(status().isOk());
        assertThat(ActionAwareOpaClient.askedActions)
                .containsExactly("catalog:update", "catalog:assign-tags");
    }

    @Test // I15c — a denied catalog:assign-tags (WRITE-no-TAG) leaves the row untouched
    void catalogDeniedAssignTagsLeavesTheEntityUntouched() throws Exception {
        var catalog = seedCatalog(Map.of("region", List.of("emea")));
        Integer versionBefore = catalogs.findById(catalog.getId()).orElseThrow().getVersion();
        ActionAwareOpaClient.rule = allowOnly("catalog:update"); // TAG is what's missing

        mockMvc.perform(put("/api/v1/catalogs/{id}", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"dispatch-it-catalog\",\"tags\":{\"region\":[\"apac\"]}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        var row = catalogs.findById(catalog.getId()).orElseThrow();
        assertThat(row.getName()).isEqualTo("dispatch-it-catalog");
        assertThat(row.getTags().asMap()).containsEntry("region", List.of("emea"));
        assertThat(row.getVersion()).isEqualTo(versionBefore); // the deny preceded ANY mutation
    }

    @Test // I16c — catalog create-with-tags is the UNCONDITIONAL 422 (unlike the category's
    // type-level assign-tags decision): a new catalog has no team yet, so the decision could never
    // resolve — rejected loudly AFTER the create gate allowed, and nothing persists.
    void catalogCreateWithTagsAnswers422PersistsNothing() throws Exception {
        long before = catalogs.count();
        ActionAwareOpaClient.rule = allowOnly("catalog:create");

        mockMvc.perform(post("/api/v1/catalogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tagged-at-birth\",\"tags\":{\"region\":[\"emea\"]}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TAG_VALUE_ILLEGAL"));

        assertThat(ActionAwareOpaClient.askedActions).containsExactly("catalog:create");
        assertThat(catalogs.count()).isEqualTo(before); // nothing persisted
    }

    // --- seeding ----------------------------------------------------------------

    private CatalogEntity seedCatalog() {
        return seedCatalog(Map.of());
    }

    private CatalogEntity seedCatalog(Map<String, Object> tags) {
        var entity = new CatalogEntity(UUID.randomUUID(), "dispatch-it-catalog", null);
        if (!tags.isEmpty()) {
            entity.setTags(ResourceTags.fromMap(tags));
        }
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

    // --- the test doubles ---------------------------------------------------------

    @TestConfiguration
    static class DispatchTestConfig {

        static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-00000000d15a");

        @Bean
        AbacSubjectExtractor dispatchSubjectExtractor() {
            AbacContext.Subject member = new AbacContext.Subject(
                    MEMBER.toString(), List.of("member"), Map.of("username", "dispatch-member"));
            return request -> Optional.of(member);
        }

        @Bean
        OpaClient actionAwareOpaClient() {
            return new ActionAwareOpaClient();
        }

        /** The legal tag vocabulary, so tags deltas pass validation and reach the dispatch. */
        @Bean
        @Primary
        TagDefinitionClient dispatchTagDefinitionClient() {
            return new TagDefinitionClient(new com.fasterxml.jackson.databind.ObjectMapper(),
                    "http://unused", 100) {
                @Override
                public List<TagDefinitionView> fetchApplicable(String resourceType, String resourceId) {
                    return List.of(new TagDefinitionView(
                            "region", "ENUM", "MULTI", List.of("emea", "amer", "apac"), null));
                }
            };
        }
    }

    /** Decides per fine ACTION and records the asked sequence — the dispatch made observable. */
    static final class ActionAwareOpaClient implements OpaClient {
        static volatile Predicate<String> rule = action -> false;
        static final List<String> askedActions = new CopyOnWriteArrayList<>();

        @Override
        public boolean allow(AbacContext context) {
            askedActions.add(context.action());
            return rule.test(context.action());
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.allowAll(); // list rows are not under test here
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return Collections.nCopies(contexts.size(), Boolean.TRUE);
        }
    }
}
