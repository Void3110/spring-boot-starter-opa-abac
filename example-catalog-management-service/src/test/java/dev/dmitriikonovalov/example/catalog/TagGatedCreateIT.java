package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dmitriikonovalov.example.catalog.TagDecisionGateIT.ActionAwareOpaClient;
import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TAG-GATED-CREATE T3 (I1–I6, real Postgres): what the create and re-parent gates put on the wire
 * (ADR 0034). The action-aware OPA stub decides by action NAME only — the tag semantics live in
 * {@code category_test.rego} / {@code product_test.rego} — so this suite pins what the gates
 * <b>declare</b>: the placement parent's tags as {@code parent_attributes} (the catalog for a
 * top-level category, the parent category when the request nests, the category for a product), the
 * raw payload as {@code attributes}, the decision sequences, the 403-before-422 order, and that a
 * denied placement moves nothing.
 */
// The OPA-edge resilience guard is OFF here for the same reason as in TagDecisionGateIT: a retried deny
// would double every denied action in the capture.
@SpringBootTest(properties = {"catalog.role-source=none", "opa.abac.resilience.opa.enabled=false"})
@Testcontainers
@AutoConfigureMockMvc
@Import(TagDecisionGateIT.DispatchTestConfig.class)
class TagGatedCreateIT {

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

    private static final Map<String, Object> EMEA = Map.of("region", List.of("emea"));
    private static final Map<String, Object> APAC = Map.of("region", List.of("apac"));

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired CatalogHierarchyService hierarchy;

    @BeforeEach
    void resetStub() {
        ActionAwareOpaClient.rule = action -> false;
        ActionAwareOpaClient.askedActions.clear();
        ActionAwareOpaClient.askedContexts.clear();
    }

    private static Predicate<String> allowOnly(String... actions) {
        var allowed = List.of(actions);
        return allowed::contains;
    }

    private static AbacContext asked(String action) {
        return ActionAwareOpaClient.askedContexts.stream()
                .filter(context -> context.action().equals(action))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no decision asked for " + action));
    }

    // --- I1: a category create puts the placement parent on the wire ----------------

    @Test
    void topLevelCategoryCreateCarriesTheCatalogAsParentAndAnEmptyPayload() throws Exception {
        var catalog = seedCatalog(EMEA);
        ActionAwareOpaClient.rule = allowOnly("category:create");

        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"top-level\"}"))
                .andExpect(status().isCreated());

        AbacContext.Resource resource = asked("category:create").resource();
        assertThat(resource.id()).isNull();
        assertThat(resource.parentAttributes()).containsAllEntriesOf(EMEA);
        assertThat(resource.rootAttributes()).containsAllEntriesOf(EMEA); // ADR 0032, unchanged
        assertThat(resource.attributes()).isEmpty();                       // no tags → an untagged create
    }

    @Test
    void nestedCategoryCreateCarriesTheParentCategoryNotTheCatalog() throws Exception {
        var catalog = seedCatalog(EMEA);
        var parent = seedCategory(catalog.getId(), null, "apac-parent", APAC);
        ActionAwareOpaClient.rule = allowOnly("category:create", "category:assign-tags");

        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"nested\",\"parentId\":\"" + parent.getId()
                                + "\",\"tags\":{\"region\":[\"emea\"]}}"))
                .andExpect(status().isCreated());

        AbacContext.Resource resource = asked("category:create").resource();
        assertThat(resource.parentAttributes()).containsAllEntriesOf(APAC); // the parent CATEGORY
        assertThat(resource.rootAttributes()).containsAllEntriesOf(EMEA);   // the catalog, still
        assertThat(resource.attributes()).containsEntry("region", List.of("emea"));
    }

    // --- I2: a product create carries the category's tags, not the catalog's --------

    @Test
    void productCreateCarriesTheCategoryAsParent() throws Exception {
        var catalog = seedCatalog(EMEA);
        var category = seedCategory(catalog.getId(), null, "apac-cat", APAC);
        ActionAwareOpaClient.rule = allowOnly("product:create", "product:assign-tags");

        mockMvc.perform(post("/api/v1/catalogs/{c}/categories/{k}/products", catalog.getId(), category.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"p\",\"priceCents\":100,\"currency\":\"USD\","
                                + "\"tags\":{\"region\":[\"emea\"]}}"))
                .andExpect(status().isCreated());

        AbacContext.Resource resource = asked("product:create").resource();
        assertThat(resource.type()).isEqualTo("product");
        assertThat(resource.parentAttributes()).containsAllEntriesOf(APAC);
        assertThat(resource.rootAttributes()).containsAllEntriesOf(EMEA);
        assertThat(resource.attributes()).containsEntry("region", List.of("emea"));
    }

    // --- I3: the second decision carries the same inputs; the sequences are unchanged --

    @Test
    void theAssignTagsDecisionCarriesTheSamePlacementInputs() throws Exception {
        var catalog = seedCatalog(EMEA);
        ActionAwareOpaClient.rule = allowOnly("category:create", "category:assign-tags");

        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tagged\",\"tags\":{\"region\":[\"emea\"]}}"))
                .andExpect(status().isCreated());

        assertThat(ActionAwareOpaClient.askedActions)
                .containsExactly("category:create", "category:assign-tags");
        AbacContext.Resource create = asked("category:create").resource();
        AbacContext.Resource assign = asked("category:assign-tags").resource();
        assertThat(assign.parentAttributes()).isEqualTo(create.parentAttributes());
        assertThat(assign.attributes()).isEqualTo(create.attributes());
        assertThat(assign.attributes()).containsEntry("region", List.of("emea"));
    }

    @Test
    void aProductCreateWithTagsAsksBothWithTheSameInputs() throws Exception {
        var catalog = seedCatalog(EMEA);
        var category = seedCategory(catalog.getId(), null, "cat", APAC);
        ActionAwareOpaClient.rule = allowOnly("product:create", "product:assign-tags");

        mockMvc.perform(post("/api/v1/catalogs/{c}/categories/{k}/products", catalog.getId(), category.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"p\",\"priceCents\":100,\"currency\":\"USD\","
                                + "\"tags\":{\"region\":[\"emea\"]}}"))
                .andExpect(status().isCreated());

        assertThat(ActionAwareOpaClient.askedActions)
                .containsExactly("product:create", "product:assign-tags");
        assertThat(asked("product:assign-tags").resource().parentAttributes())
                .isEqualTo(asked("product:create").resource().parentAttributes())
                .containsAllEntriesOf(APAC);
    }

    // --- I4: a deny persists nothing and leaks nothing ------------------------------

    @Test
    void aDeniedCategoryCreateAnswers403NotTheValidationVocabulary() throws Exception {
        var catalog = seedCatalog(EMEA);
        long before = categories.count();
        ActionAwareOpaClient.rule = action -> false;

        // An UNKNOWN tag key: validation would answer 422 — but authorization runs first, on the raw map.
        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"denied\",\"tags\":{\"bogus\":[\"x\"]}}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        assertThat(ActionAwareOpaClient.askedActions).containsExactly("category:create");
        assertThat(asked("category:create").resource().attributes()).containsKey("bogus"); // raw, unvalidated
        assertThat(categories.count()).isEqualTo(before);
    }

    @Test
    void aDeniedProductCreateAnswers403NotTheValidationVocabulary() throws Exception {
        var catalog = seedCatalog(EMEA);
        var category = seedCategory(catalog.getId(), null, "cat", APAC);
        long before = products.count();
        ActionAwareOpaClient.rule = action -> false;

        mockMvc.perform(post("/api/v1/catalogs/{c}/categories/{k}/products", catalog.getId(), category.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"p\",\"priceCents\":100,\"currency\":\"USD\","
                                + "\"tags\":{\"bogus\":[\"x\"]}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        assertThat(products.count()).isEqualTo(before);
    }

    // --- I5: re-parent is a placement -----------------------------------------------

    @Test
    void aReparentAsksThePlacementOnTheNewParentAndADenyMovesNothing() throws Exception {
        var catalog = seedCatalog(EMEA);
        var parentA = seedCategory(catalog.getId(), null, "A", EMEA);
        var parentB = seedCategory(catalog.getId(), null, "B", APAC);
        var child = seedCategory(catalog.getId(), parentA.getId(), "child", EMEA);
        ActionAwareOpaClient.rule = allowOnly("category:update"); // the placement (create) is DENIED

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), child.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"child\",\"parentId\":\"" + parentB.getId()
                                + "\",\"tags\":{\"region\":[\"emea\"]}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        assertThat(ActionAwareOpaClient.askedActions)
                .containsExactly("category:update", "category:create");
        AbacContext.Resource placement = asked("category:create").resource();
        assertThat(placement.id()).isNull();                               // create-shaped, type-level
        assertThat(placement.parentAttributes()).containsAllEntriesOf(APAC); // the NEW parent's tags
        assertThat(placement.attributes()).containsEntry("region", List.of("emea"));

        CategoryEntity unmoved = categories.findById(child.getId()).orElseThrow();
        assertThat(unmoved.getParentId()).isEqualTo(parentA.getId());
        assertThat(unmoved.getPath()).isEqualTo(child.getPath());
    }

    @Test
    void anUpdateWithTheSameParentAsksNoPlacement() throws Exception {
        var catalog = seedCatalog(EMEA);
        var parentA = seedCategory(catalog.getId(), null, "A", EMEA);
        var child = seedCategory(catalog.getId(), parentA.getId(), "child", EMEA);
        ActionAwareOpaClient.rule = allowOnly("category:update");

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), child.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed\",\"parentId\":\"" + parentA.getId()
                                + "\",\"tags\":{\"region\":[\"emea\"]}}"))
                .andExpect(status().isOk());

        assertThat(ActionAwareOpaClient.askedActions).containsExactly("category:update");
    }

    @Test
    void aMoveToTheRootAsksThePlacementWithTheCatalogAsParent() throws Exception {
        var catalog = seedCatalog(EMEA);
        var parentA = seedCategory(catalog.getId(), null, "A", APAC);
        var child = seedCategory(catalog.getId(), parentA.getId(), "child", EMEA);
        ActionAwareOpaClient.rule = allowOnly("category:update", "category:create");

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}", catalog.getId(), child.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"child\",\"tags\":{\"region\":[\"emea\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").doesNotExist());

        assertThat(ActionAwareOpaClient.askedActions)
                .containsExactly("category:update", "category:create");
        assertThat(asked("category:create").resource().parentAttributes()).containsAllEntriesOf(EMEA);
        assertThat(categories.findById(child.getId()).orElseThrow().getParentId()).isNull();
    }

    // --- I6: a missing parent is a 404 after the gate, never a 500 -------------------

    @Test
    void aMissingParentIsAbsentOnTheWireAndA404FromTheBody() throws Exception {
        var catalog = seedCatalog(EMEA);
        ActionAwareOpaClient.rule = allowOnly("category:create");

        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"orphan\",\"parentId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        // The resolver miss is "unproven": the field is ABSENT (null), never an empty map — and the
        // gate let a no-requirement decision through to the body's existence check.
        assertThat(asked("category:create").resource().parentAttributes()).isNull();
    }

    @Test
    void aParentInAnotherCatalogIsUnprovenOnTheWireAndA404FromTheBody() throws Exception {
        var catalogA = seedCatalog(EMEA);
        var catalogB = seedCatalog(EMEA);
        var foreign = seedCategory(catalogB.getId(), null, "foreign-tagged", EMEA); // its tags would match
        ActionAwareOpaClient.rule = allowOnly("category:create");

        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalogA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cross\",\"parentId\":\"" + foreign.getId() + "\"}"))
                .andExpect(status().isNotFound());

        // Confinement (ADR 0034): the parent resolves by id, but its chain roots at catalog B, not at the
        // governing catalog A — so the field is ABSENT and the foreign tags never reach the decision. A
        // tag-requiring role gets the same 403 as for a mismatching parent; nothing leaks by status code.
        assertThat(asked("category:create").resource().parentAttributes()).isNull();
        assertThat(asked("category:create").resource().rootAttributes()).containsAllEntriesOf(EMEA);
    }

    // --- seeding ----------------------------------------------------------------

    private CatalogEntity seedCatalog(Map<String, Object> tags) {
        var entity = new CatalogEntity(UUID.randomUUID(), "placement-it-catalog", null);
        if (!tags.isEmpty()) {
            entity.setTags(ResourceTags.fromMap(tags));
        }
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
}
