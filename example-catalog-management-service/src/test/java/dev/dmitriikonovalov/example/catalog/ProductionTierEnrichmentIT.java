package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.config.CatalogResourceResolver;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionClient;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionView;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.example.catalog.domain.ProductEntity;
import dev.dmitriikonovalov.example.catalog.domain.ProductRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.AbacResourceResolver;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * PRODUCTION-TIER <b>T5</b> — the four child endpoints' tier behavior <em>below the rig</em>, against real
 * Postgres (Testcontainers, never H2) and a recording OPA stub (in-process, no WireMock). QA cases
 * <b>I5–I8</b>.
 *
 * <h2>What this suite proves, and what it deliberately does not</h2>
 * The tier <em>decision</em> is policy's (proved by {@code opa test}, T4) and its end-to-end behavior is the
 * rig's (T6). What can only be proved here is the <b>seam between them</b>: that each of the four gated
 * endpoints ships the pinned input shape (ADR 0032) and honors the answer it gets back. So the stub OPA
 * client is not programmable — it is a <b>miniature of the shipped corpus</b>, carrying the same two
 * provenance-scoped {@code denied} clauses T4 added ({@code supervised} + absent {@code root_attributes},
 * {@code supervised} + {@code env=production}) on top of the coarse token check. A test that passed against
 * a hand-waved allow-all stub would prove nothing about the tier.
 *
 * <p>The three states must stay <b>distinguishable on the wire</b>, so the assertions run against the
 * <b>serialized</b> input as well as the object: {@code {}} (untagged root, fetched) must appear as
 * {@code "root_attributes":&#123;&#125;}, while absent (enrichment failed) must be a <b>missing key</b> —
 * not {@code null}, not {@code {}}. Collapsing those two is the fail-open the whole slice exists to
 * prevent.
 *
 * <p>Action enrichment is OFF here for the same reason {@code ResourceResolutionGateIT} turns it off: this
 * suite pins the <b>gate's</b> input shapes in isolation, and the read-side advice would add per-row
 * decisions that are not what these cells measure. The {@code _actions} omission on supervised child rows
 * is pinned end to end by T6's E7 cell.
 */
@SpringBootTest(properties = {
    "catalog.role-source=none",
    "opa.abac.action-enrichment.enabled=false"
})
@Testcontainers
@AutoConfigureMockMvc
@Import(ProductionTierEnrichmentIT.TierTestConfig.class)
class ProductionTierEnrichmentIT {

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

    static final String SUBJECT_HEADER = "X-Test-Subject";
    /** A pure supervisor — the synthesized read-only role, {@code provenance=supervised}. */
    static final String SUPERVISOR = "sup-anna";
    /** An ordinary member of the catalog's team — the population the tier must never touch. */
    static final String MEMBER = "plain-member";

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired CatalogHierarchyService hierarchy;

    @BeforeEach
    void reset() {
        products.deleteAll();
        categories.deleteAll();
        catalogs.deleteAll();
        TierStubOpaClient.allowInputs.clear();
        TierStubOpaClient.compileInputs.clear();
        RecordingResourceResolver.resolves.clear();
        RecordingResourceResolver.throwingTypes.clear();
    }

    // --- I5: the child GETs — the input carries the governing root's tag map, and the answer is honored

    @Test // I5 — both child GETs enrich from the GOVERNING CATALOG, not from the leaf's own tags
    void childGetInputsCarryTheGoverningCatalogsTagMap() throws Exception {
        var fixture = seedTree(Map.of("env", "staging"));

        getCategoryAs(SUPERVISOR, fixture).andExpect(status().isOk());
        getProductAs(SUPERVISOR, fixture).andExpect(status().isOk());

        AbacContext category = capturedFor("category:view");
        assertThat(category.resource().rootAttributes())
                .as("the category GET is judged on the CATALOG's tags, carried as root_attributes")
                .isEqualTo(Map.of("env", "staging"));
        assertThat(category.resource().ancestors())
                .extracting(p -> p.type() + ":" + p.id())
                .containsExactly("catalog:" + fixture.catalogId());

        AbacContext product = capturedFor("product:view");
        assertThat(product.resource().rootAttributes())
                .as("two levels down, the governing root is still the catalog")
                .isEqualTo(Map.of("env", "staging"));
        assertThat(product.resource().ancestors())
                .extracting(p -> p.type() + ":" + p.id())
                .containsExactly("catalog:" + fixture.catalogId(), "category:" + fixture.categoryId());
    }

    @Test // I5 — an UNTAGGED governing catalog is `{}` on the wire (fetched, untagged), and it OPENS
    void anUntaggedGoverningCatalogIsAnEmptyMapOnTheWireAndOpensTheChild() throws Exception {
        var fixture = seedTree(Map.of());

        getCategoryAs(SUPERVISOR, fixture).andExpect(status().isOk());

        Captured captured = theDecisionFor("category:view", SUPERVISOR);
        assertThat(captured.context().resource().rootAttributes())
                .as("fetched-and-untagged is an EMPTY MAP, never absent — absent would close the tier")
                .isNotNull()
                .isEmpty();
        assertThat(captured.json())
                .as("and it must survive serialization: NON_NULL, never NON_EMPTY")
                .contains("\"root_attributes\":{}");
    }

    @Test // I5 — a PRODUCTION governing catalog: the stub's tier clause denies, and the endpoint answers
    // a PLAIN 403 — no deny_reason anywhere in B (that envelope field is slice C's)
    void aProductionGoverningCatalogDeniesBothChildGetsWithAPlainForbidden() throws Exception {
        var fixture = seedTree(Map.of("env", "production"));

        String categoryBody = getCategoryAs(SUPERVISOR, fixture)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
                .andReturn().getResponse().getContentAsString();
        getProductAs(SUPERVISOR, fixture).andExpect(status().isForbidden());

        assertThat(categoryBody)
                .as("B's supervised production deny is indistinguishable on the wire from any other deny")
                .doesNotContain("deny_reason");
        assertThat(capturedFor("category:view").resource().rootAttributes())
                .isEqualTo(Map.of("env", "production"));
    }

    @Test // I5 — the member half of the same fixture: production changes nothing for a member
    void aMemberReadsTheSameProductionChildrenUnchanged() throws Exception {
        var fixture = seedTree(Map.of("env", "production"));

        getCategoryAs(MEMBER, fixture).andExpect(status().isOk());
        getProductAs(MEMBER, fixture).andExpect(status().isOk());
    }

    // --- I6: the child LISTs — the coarse gate is enriched, the residual is not --------------------

    // I6 — the type-level gate carries root_attributes from the roleResource OVERRIDE target, while the
    // Compile/filter request that follows carries none: the tier never enters the residual.
    @Test
    void theChildListGateIsEnrichedAndTheResidualIsNot() throws Exception {
        var fixture = seedTree(Map.of("env", "staging"));

        listCategoriesAs(SUPERVISOR, fixture).andExpect(status().isOk());
        listProductsAs(SUPERVISOR, fixture).andExpect(status().isOk());

        for (String action : List.of("category:list", "product:list")) {
            AbacContext gate = capturedFor(action);
            assertThat(gate.resource().id())
                    .as("%s is decided at the COARSE type-level gate", action)
                    .isNull();
            assertThat(gate.resource().rootAttributes())
                    .as("%s: enriched from the roleResource override target (the catalog)", action)
                    .isEqualTo(Map.of("env", "staging"));
        }

        assertThat(TierStubOpaClient.compileInputs)
                .as("both list endpoints reached partial evaluation")
                .hasSize(2);
        for (Captured residual : TierStubOpaClient.compileInputs) {
            assertThat(residual.context().resource().rootAttributes())
                    .as("invariant 4: nothing tier-related may reach the SQL residual")
                    .isNull();
            assertThat(residual.json()).doesNotContain("root_attributes");
        }
    }

    @Test // I6 — the list decision lands AT THE GATE: a production root closes the list before the
    // residual is ever compiled
    void aProductionRootClosesTheChildListAtTheCoarseGate() throws Exception {
        var fixture = seedTree(Map.of("env", "production"));

        listCategoriesAs(SUPERVISOR, fixture).andExpect(status().isForbidden());
        listProductsAs(SUPERVISOR, fixture).andExpect(status().isForbidden());

        assertThat(TierStubOpaClient.compileInputs)
                .as("denied at the gate — partial evaluation is never reached, so no residual exists"
                        + " that could have needed a tier predicate")
                .isEmpty();
    }

    // --- I7: enrichment failure — absent, and only the supervised population closes ----------------

    @Test // I7 — a root fetch that THROWS leaves the key absent (a MISSING key on the wire), the
    // supervised read closes, and the member's read proceeds unchanged — never a 5xx
    void aThrowingRootFetchClosesTheSupervisedPathAndLeavesTheMemberUntouched() throws Exception {
        var fixture = seedTree(Map.of("env", "staging"));
        RecordingResourceResolver.throwingTypes.add("catalog");

        getCategoryAs(SUPERVISOR, fixture)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        getCategoryAs(MEMBER, fixture)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.categoryId().toString()));

        // Both populations were still ASKED — an enrichment outage never becomes an exception out of the
        // manager, and never a 5xx. What separates them is the answer, which is policy's to give.
        for (String persona : List.of(SUPERVISOR, MEMBER)) {
            Captured captured = theDecisionFor("category:view", persona);
            assertThat(captured.context().resource().rootAttributes())
                    .as("%s: unproven is ABSENT — never an empty map, which reads as 'untagged' and OPENS",
                            persona)
                    .isNull();
            assertThat(captured.json())
                    .as("%s: and absent is a MISSING KEY on the wire, not a serialized null", persona)
                    .doesNotContain("root_attributes");
        }
    }

    @Test // I7 — the same outage on the LIST gate: closed for the supervisor, open for the member
    void aThrowingRootFetchClosesTheSupervisedListGateOnly() throws Exception {
        var fixture = seedTree(Map.of("env", "staging"));
        RecordingResourceResolver.throwingTypes.add("catalog");

        listCategoriesAs(SUPERVISOR, fixture).andExpect(status().isForbidden());
        listCategoriesAs(MEMBER, fixture).andExpect(status().isOk());

        assertThat(theDecisionFor("category:list", SUPERVISOR).context().resource().rootAttributes())
                .as("the supervisor's gate could not prove the tier")
                .isNull();
        assertThat(theDecisionFor("category:list", MEMBER).context().resource().rootAttributes())
                .as("neither could the member's — and it changed nothing for them")
                .isNull();
    }

    // --- I8: one root fetch per request ------------------------------------------------------------

    @Test // I8 — a category PUT that changes BOTH content and tags asks two decisions (update +
    // assign-tags), each of which enriches from the same governing root. The read-through memo means
    // exactly ONE root resolve — and both decisions see the SAME snapshot (never a mixed view).
    void theGoverningRootIsResolvedOnceAcrossTwoDecisionsInOneRequest() throws Exception {
        var fixture = seedTree(Map.of("env", "staging"));

        mockMvc.perform(put("/api/v1/catalogs/{c}/categories/{id}",
                                fixture.catalogId(), fixture.categoryId())
                        .header(SUBJECT_HEADER, MEMBER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed\",\"tags\":{\"region\":\"emea\"}}"))
                .andExpect(status().isOk());

        assertThat(TierStubOpaClient.allowInputs)
                .extracting(c -> c.context().action())
                .as("the delta dispatch asked both questions")
                .containsExactly("category:update", "category:assign-tags");
        assertThat(RecordingResourceResolver.count("catalog"))
                .as("the governing root is resolved ONCE per request (RequestAttributesResourceCache)")
                .isEqualTo(1);
        assertThat(RecordingResourceResolver.count("category"))
                .as("while the DECIDED LEAF is resolved fresh for each decision — never read back from the"
                        + " memo, so no decision can read its own cached answer")
                .isEqualTo(2);
        assertThat(TierStubOpaClient.allowInputs)
                .extracting(c -> c.context().resource().rootAttributes())
                .as("and both decisions saw one coherent root snapshot")
                .containsExactly(Map.of("env", "staging"), Map.of("env", "staging"));
    }

    // --- requests ----------------------------------------------------------------------------------

    private ResultActions getCategoryAs(String subject, Fixture fixture) throws Exception {
        return mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{id}",
                        fixture.catalogId(), fixture.categoryId())
                .header(SUBJECT_HEADER, subject));
    }

    private ResultActions getProductAs(String subject, Fixture fixture) throws Exception {
        return mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{cat}/products/{p}",
                        fixture.catalogId(), fixture.categoryId(), fixture.productId())
                .header(SUBJECT_HEADER, subject));
    }

    private ResultActions listCategoriesAs(String subject, Fixture fixture) throws Exception {
        return mockMvc.perform(get("/api/v1/catalogs/{c}/categories", fixture.catalogId())
                .header(SUBJECT_HEADER, subject));
    }

    private ResultActions listProductsAs(String subject, Fixture fixture) throws Exception {
        return mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{cat}/products",
                        fixture.catalogId(), fixture.categoryId())
                .header(SUBJECT_HEADER, subject));
    }

    // --- captures ----------------------------------------------------------------------------------

    private static AbacContext capturedFor(String action) {
        return theDecisionFor(action, null).context();
    }

    /**
     * The decision recorded for {@code action} (optionally narrowed to one subject).
     *
     * <p><b>Why this does not assert a count.</b> A <em>denied</em> check reaches the stub more than once:
     * {@code ResilientOpaClient.allow} treats the fail-closed sentinel {@code false} as retryable, so a
     * genuine deny costs one extra sidecar hop before the guard's budget is spent (B3's deliberate
     * posture — the sentinel is retried but never recorded on the breaker). The attempt count is
     * therefore a resilience property, not a tier one. What this suite pins instead is stronger and is
     * what the tier depends on: <b>every attempt carried byte-identical input</b>.
     */
    private static Captured theDecisionFor(String action, String subject) {
        List<Captured> matching = TierStubOpaClient.allowInputs.stream()
                .filter(c -> c.context().action().equals(action))
                .filter(c -> subject == null || c.context().subject().id().equals(subject))
                .toList();
        assertThat(matching)
                .as("decisions recorded for '%s'%s", action, subject == null ? "" : " by " + subject)
                .isNotEmpty();
        assertThat(matching)
                .extracting(Captured::json)
                .as("every attempt at one decision must carry the same input")
                .containsOnly(matching.get(0).json());
        return matching.get(0);
    }

    // --- seeding -----------------------------------------------------------------------------------

    /** A catalog tagged as the case requires, one category under it, one product under that. */
    private Fixture seedTree(Map<String, Object> catalogTags) {
        var catalog = new CatalogEntity(UUID.randomUUID(), "tier-catalog", "the governing root");
        if (!catalogTags.isEmpty()) {
            catalog.setTags(ResourceTags.fromMap(catalogTags));
        }
        hierarchy.assignPath(catalog);
        catalog = catalogs.save(catalog);

        var category = new CategoryEntity(UUID.randomUUID(), catalog.getId(), null, "tier-category", null);
        hierarchy.assignPath(category);
        category = categories.save(category);

        var product = new ProductEntity(
                UUID.randomUUID(), category.getId(), "tier-product", null, "SKU-1", 100L, "USD");
        hierarchy.assignPath(product);
        product = products.save(product);

        return new Fixture(catalog.getId(), category.getId(), product.getId());
    }

    private record Fixture(UUID catalogId, UUID categoryId, UUID productId) {}

    // --- test doubles ------------------------------------------------------------------------------

    @TestConfiguration
    static class TierTestConfig {

        /** The persona comes from a request header, so one context exercises both populations. */
        @Bean
        AbacSubjectExtractor tierSubjectExtractor() {
            return request -> {
                String subject = request.getHeader(SUBJECT_HEADER);
                return subject == null
                        ? Optional.empty()
                        : Optional.of(new AbacContext.Subject(subject, List.of(), Map.of()));
            };
        }

        /**
         * The two role shapes this slice cares about: the <b>synthesized supervisor</b> role as T4 widened
         * it (READ on the catalog <em>and</em> both child types — contents open by DIRECT grant, never by
         * inheritance), and an ordinary <b>membership</b> role. The {@code provenance} attribute is what
         * the tier clauses key on.
         */
        @Bean
        RoleDefinitionSupplier tierRoleSupplier() {
            return (userId, type, id) -> Optional.of(userId.startsWith("sup-")
                    ? new RoleDefinition(
                            "supervisor-readonly",
                            Map.of("provenance", "supervised"),
                            Map.of(
                                    "catalog", List.of("READ"),
                                    "category", List.of("READ"),
                                    "product", List.of("READ")))
                    : new RoleDefinition(
                            "owner",
                            Map.of("provenance", "membership"),
                            Map.of(
                                    "catalog", List.of("READ", "WRITE", "TAG"),
                                    "category", List.of("READ", "WRITE", "TAG"),
                                    "product", List.of("READ", "WRITE", "TAG"))));
        }

        @Bean
        @Primary
        OpaClient tierStubOpaClient() {
            return new TierStubOpaClient();
        }

        /**
         * Wraps the app's own resolver so root fetches can be counted (I8) and made to throw (I7). The
         * delegate is named by its concrete type deliberately — {@code AbacResourceResolver} would make
         * this factory method a candidate for its own argument.
         */
        @Bean
        @Primary
        AbacResourceResolver recordingResourceResolver(CatalogResourceResolver delegate) {
            return new RecordingResourceResolver(delegate);
        }

        /**
         * The dictionary the I8 PUT consults. Since T2 a tags-carrying write is validated against the
         * dictionary, and without a stub the real client would reach for an absent user-service and fail
         * closed with 503 — correct, but not what this suite measures.
         */
        @Bean
        @Primary
        TagDefinitionClient tierTagDefinitionClient() {
            return new TagDefinitionClient(new ObjectMapper(), "http://unused", 100) {
                @Override
                public List<TagDefinitionView> fetchApplicable(String resourceType, String resourceId) {
                    return List.of(new TagDefinitionView(
                            "region", "ENUM", "SINGLE", List.of("emea", "amer", "apac"), null, false));
                }
            };
        }
    }

    /** One recorded decision: the context object, and the bytes it would have gone to OPA as. */
    record Captured(AbacContext context, String json) {}

    /**
     * A miniature of the shipped corpus: the coarse token check, <b>plus the two provenance-scoped tier
     * clauses T4 added</b> — supervised + absent {@code root_attributes} (unproven ⇒ closed) and
     * supervised + {@code env=production}. Written as two clauses rather than one negation for the same
     * reason the Rego is: {@code !"production".equals(root.get("env"))} would pass for an absent root and
     * fail OPEN.
     */
    static final class TierStubOpaClient implements OpaClient {

        static final ObjectMapper MAPPER = new ObjectMapper();
        static final List<Captured> allowInputs = new CopyOnWriteArrayList<>();
        static final List<Captured> compileInputs = new CopyOnWriteArrayList<>();

        @Override
        public boolean allow(AbacContext context) {
            allowInputs.add(capture(context));
            return verdictFor(context);
        }

        @Override
        public PartialResult compile(AbacContext context) {
            compileInputs.add(capture(context));
            return PartialResult.allowAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return contexts.stream().map(TierStubOpaClient::verdictFor).toList();
        }

        private static Captured capture(AbacContext context) {
            return new Captured(context, MAPPER.writeValueAsString(context));
        }

        private static boolean verdictFor(AbacContext context) {
            RoleDefinition role = context.roleDefinition();
            if (role == null) {
                return false;
            }
            if ("supervised".equals(role.attributes().get("provenance"))) {
                Map<String, Object> root = context.resource().rootAttributes();
                if (root == null) {
                    return false; // tier unproven — the absent clause
                }
                if ("production".equals(root.get("env"))) {
                    return false; // tier proven production
                }
            }
            List<String> tokens = role.permissions().getOrDefault(context.resource().type(), List.of());
            String verb = context.action().substring(context.action().indexOf(':') + 1);
            return switch (verb) {
                case "view", "list" -> tokens.contains("READ");
                case "update", "delete" -> tokens.contains("WRITE");
                case "assign-tags" -> tokens.contains("TAG");
                default -> false;
            };
        }
    }

    /** Counts resolutions per type and can fail a type's fetch — the two hooks I7 and I8 need. */
    static final class RecordingResourceResolver implements AbacResourceResolver {

        static final Map<String, AtomicInteger> resolves = new ConcurrentHashMap<>();
        static final Set<String> throwingTypes = ConcurrentHashMap.newKeySet();

        private final AbacResourceResolver delegate;

        RecordingResourceResolver(AbacResourceResolver delegate) {
            this.delegate = delegate;
        }

        static int count(String resourceType) {
            AtomicInteger counter = resolves.get(resourceType);
            return counter == null ? 0 : counter.get();
        }

        @Override
        public Optional<AbacResource> resolve(String resourceType, String resourceId) {
            resolves.computeIfAbsent(resourceType, t -> new AtomicInteger()).incrementAndGet();
            if (throwingTypes.contains(resourceType)) {
                throw new IllegalStateException(
                        "simulated resolution failure for " + resourceType + "/" + resourceId);
            }
            return delegate.resolve(resourceType, resourceId);
        }
    }
}
