package dev.dmitriikonovalov.example.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.config.SupervisedScopeClient;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.filter.GovernedScopeResolver;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * STEP-UP-ELEVATION <b>T4</b>, QA case <b>I3</b> — the <b>third prong</b> of ADR 0030 Amendment 4's
 * human-only supervised path: the catalog list's supervised leg closes to agent-marked calls, app-side.
 *
 * <h2>Why this one cannot be a policy test</h2>
 * The list's cut comes from {@code filter}, which deliberately never consults {@code denied} (slice B's
 * pin, asserted positively in the policy tests). So no Rego deny can reach this leg, and the two
 * single-decision prongs — the leaf policies' provenance-scoped agent deny — leave exactly this hole. The
 * closure is the {@code act_chain} <b>key</b> presence-test in {@code CatalogListAuthorizer}, and this
 * suite is where it is proved, against real Postgres and the real {@code SupervisedScopeClient}.
 *
 * <h2>The three cells, and the one that would be a silent fail-open</h2>
 * A pure supervisor's agent call must see the <b>empty page</b>; the same subject <em>without</em> the
 * claim must see their supervised rows (otherwise the empty page proves nothing); and a <b>member's</b>
 * agent call must be <b>unchanged</b> — the guard drops a leg, and members do not have that leg.
 */
@SpringBootTest(properties = {
    "catalog.role-source=none",
    "opa.abac.action-enrichment.enabled=false"
})
@Testcontainers
@AutoConfigureMockMvc
@Import(AgentSupervisedLegIT.AgentLegTestConfig.class)
class AgentSupervisedLegIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    /** The stand-in user-service, so the REAL SupervisedScopeClient runs rather than a mock of it. */
    static final HttpServer USER_SERVICE = startStub();

    static final Map<String, List<UUID>> SUPERVISED = new ConcurrentHashMap<>();
    static final Map<String, List<UUID>> GOVERNED = new ConcurrentHashMap<>();

    static {
        POSTGRES.start();
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/supervised-targets", exchange -> {
                try {
                    String subject = queryParam(exchange.getRequestURI().getQuery());
                    String body = SUPERVISED.getOrDefault(subject, List.of()).stream()
                            .map(id -> "\"" + id + "\"")
                            .collect(Collectors.joining(",", "[", "]"));
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("could not start the user-service stub", e);
        }
    }

    private static String queryParam(String query) {
        for (String pair : query.split("&")) {
            if (pair.startsWith("subject=")) {
                return java.net.URLDecoder.decode(
                        pair.substring("subject=".length()), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopStub() {
        USER_SERVICE.stop(0);
    }

    private static final String SUBJECT_HEADER = "X-Test-Subject";
    /** The header that stands in for the token's delegation claim; its PRESENCE is the discriminator. */
    private static final String AGENT_HEADER = "X-Test-Act-Chain";

    private static final String ANNA = "sup-anna";        // a pure supervisor — member of no team
    private static final String MEMBER = "plain-member";  // an ordinary member

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;
    @Autowired CatalogHierarchyService hierarchy;

    private UUID supervisedCatalog;
    private UUID memberCatalog;

    @BeforeEach
    void seed() {
        catalogs.deleteAll();
        GOVERNED.clear();
        SUPERVISED.clear();

        supervisedCatalog = saveCatalog("supervised-catalog");
        memberCatalog = saveCatalog("member-catalog");

        GOVERNED.put(MEMBER, List.of(memberCatalog));
        SUPERVISED.put(ANNA, List.of(supervisedCatalog));
        // The member also supervises nothing — their page comes purely from the membership leg.
    }

    private UUID saveCatalog(String name) {
        CatalogEntity entity = new CatalogEntity(UUID.randomUUID(), name, name + " description");
        entity.setTags(ResourceTags.fromMap(Map.of()));
        hierarchy.assignPath(entity);
        return catalogs.save(entity).getId();
    }

    private ResultActions listAs(String subject, String actChain) throws Exception {
        // page is 0-BASED: asking for page 1 returns an empty second page whatever the cut did, which
        // would make every "empty page" assertion below vacuously green.
        var request = get("/api/v1/catalogs").param("page", "0").param("perPage", "20")
                .header(SUBJECT_HEADER, subject);
        if (actChain != null) {
            request = request.header(AGENT_HEADER, actChain);
        }
        return mockMvc.perform(request);
    }

    // --- I3 -----------------------------------------------------------------------------

    @Test // the control: a pure supervisor WITHOUT the claim sees their supervised row
    void aHumanSupervisorSeesTheSupervisedLeg() throws Exception {
        listAs(ANNA, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].id").value(supervisedCatalog.toString()));
    }

    @Test // the cell: the same subject WITH the delegation claim loses the leg → the empty page
    void anAgentMarkedSupervisorSeesTheEmptyPage() throws Exception {
        listAs(ANNA, "[\"agent-readonly\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test // PRESENCE, not truthiness: every falsy/empty claim shape still closes the leg
    void everyFalsyClaimShapeStillClosesTheLeg() throws Exception {
        for (String shape : List.of("false", "[]", "", "0", "null")) {
            listAs(ANNA, shape)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(0));
        }
    }

    @Test // a MEMBER's agent call is untouched — the guard drops a leg members do not have
    void aMembersAgentCallIsUnchanged() throws Exception {
        listAs(MEMBER, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].id").value(memberCatalog.toString()));

        listAs(MEMBER, "[\"agent-readonly\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].id").value(memberCatalog.toString()));
    }

    @Test // I2 (ADR 0033): the agent degrade branch still labels HONESTLY — membership rows, all "member"
    void anAgentMarkedCallLabelsItsSurvivingRowsMember() throws Exception {
        // The supervised leg is skipped for an agent-marked call, so the supervised id set is EMPTY —
        // present-but-empty, not absent. The rows that survive really did arrive by membership, so they
        // must be labelled `member` rather than left unlabelled: a degrade branch that can still answer
        // honestly does. (An agent-marked PURE supervisor gets the empty page, so there is nothing to
        // label there — that is the cell above.)
        listAs(MEMBER, "[\"agent-readonly\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].id").value(memberCatalog.toString()))
                .andExpect(jsonPath("$.items[0]._provenance").value("member"));

        // And the same subject WITHOUT the claim is labelled identically — the label tracks the leg the
        // row arrived on, not whether the request was agent-marked.
        listAs(MEMBER, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]._provenance").value("member"));
    }

    // --- test doubles --------------------------------------------------------------------

    @TestConfiguration
    static class AgentLegTestConfig {

        /**
         * The subject comes from headers: the persona, plus an optional {@code act_chain} attribute
         * standing in for what the service's {@code attribute-claims} config copies off a real token. The
         * header is added <b>only</b> when present, so "absent" is a genuinely missing key rather than a
         * null — which is the distinction the presence-test rests on.
         */
        @Bean
        AbacSubjectExtractor agentAwareSubjectExtractor() {
            return request -> {
                String subject = request.getHeader(SUBJECT_HEADER);
                if (subject == null) {
                    return Optional.empty();
                }
                String actChain = request.getHeader(AGENT_HEADER);
                Map<String, Object> attributes = actChain == null
                        ? Map.of()
                        : Map.of("act_chain", parseClaim(actChain));
                return Optional.of(new AbacContext.Subject(subject, List.of(), attributes));
            };
        }

        /** The falsy/empty shapes a real token could carry, kept as their Java equivalents. */
        private static Object parseClaim(String raw) {
            return switch (raw) {
                case "false" -> Boolean.FALSE;
                case "[]" -> List.of();
                case "0" -> 0;
                case "null", "" -> "";
                default -> List.of("agent-readonly");
            };
        }

        @Bean
        GovernedScopeResolver testGovernedScopeResolver() {
            return (subject, resourceType) -> GOVERNED.getOrDefault(subject, List.of());
        }

        /** The REAL client, pointed at the in-process stub user-service. */
        @Bean
        SupervisedScopeClient testSupervisedScopeClient() {
            return new SupervisedScopeClient(
                    new tools.jackson.databind.ObjectMapper(),
                    "http://127.0.0.1:" + USER_SERVICE.getAddress().getPort(),
                    2000);
        }

        @Bean
        RoleDefinitionSupplier agentAwareRoleSupplier() {
            return (userId, type, id) -> {
                UUID resourceId = UUID.fromString(id);
                if (GOVERNED.getOrDefault(userId, List.of()).contains(resourceId)) {
                    return Optional.of(new RoleDefinition("owner",
                            Map.of("provenance", "membership"),
                            Map.of("catalog", List.of("READ", "WRITE", "TAG"))));
                }
                if (SUPERVISED.getOrDefault(userId, List.of()).contains(resourceId)) {
                    return Optional.of(new RoleDefinition("supervisor-readonly",
                            Map.of("provenance", "supervised"),
                            Map.of("catalog", List.of("READ"))));
                }
                return Optional.empty();
            };
        }

        @Bean
        @Primary
        OpaClient agentLegStubOpaClient() {
            return new AllowAllStubOpaClient();
        }
    }

    /**
     * An allow-all stub on purpose: the cut under test is the <b>scope</b> the app composes, so a policy
     * that narrowed anything would make an empty page ambiguous — it must be the missing leg, not a deny.
     */
    static final class AllowAllStubOpaClient implements OpaClient {

        @Override
        public boolean allow(AbacContext context) {
            return true;
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.allowAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return contexts.stream().map(c -> true).toList();
        }
    }
}
