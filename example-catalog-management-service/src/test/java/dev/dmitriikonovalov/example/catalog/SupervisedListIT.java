package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.config.SupervisedScopeClient;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.Condition;
import dev.dmitriikonovalov.opaabac.core.Conjunction;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The two-leg partitioned catalog list against <b>real Postgres</b> — QA <b>I4</b> (paged union), <b>I5</b>
 * (the read-only {@code _actions} ceiling), <b>I6</b> (the audit event) and <b>U31</b> (the wire
 * {@code count} across both legs), entered through the real {@code GET /api/v1/catalogs} endpoint.
 *
 * <h2>Why the OPA stub returns a CONDITIONAL residual, not ALLOW_ALL</h2>
 * With an ALLOW_ALL membership residual the {@code subtreeSpec} arm would be redundant and the test would
 * pass even if the composition were wrong. So {@code compile} returns a real DNF —
 * {@code tags.region = 'emea'} — which the library translates to SQL. That makes the cut load-bearing:
 * <ul>
 *   <li>a membership row tagged {@code emea} survives the <b>membership residual</b>;</li>
 *   <li>a membership row tagged {@code apac} does <b>not</b> — and must stay excluded even though the
 *       subject also supervises catalogs, because {@code supervised := S \ M} keeps it off the widening
 *       arm. <b>This is the slice's fail-open edge</b>: had the reduction been skipped or inverted, the
 *       {@code apac} row would ride the vacuous supervisor arm into the page;</li>
 *   <li>a supervised row (tagged {@code apac}, so the membership residual rejects it) survives <b>only</b>
 *       through the {@code subtreeSpec} arm — which is the two-leg composition doing its job.</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "catalog.role-source=none",
    "opa.abac.action-enrichment.enabled=true"
})
@Testcontainers
@AutoConfigureMockMvc
@Import(SupervisedListIT.SupervisedListTestConfig.class)
class SupervisedListIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    /** The stand-in user-service, so the REAL SupervisedScopeClient is exercised, not a mock of it. */
    static final HttpServer USER_SERVICE = startStub();

    /** subject → the raw supervised ids the stub user-service answers with. */
    static final Map<String, List<UUID>> SUPERVISED = new ConcurrentHashMap<>();

    /** subject → the membership-governed ids. */
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
                return java.net.URLDecoder.decode(pair.substring("subject=".length()), StandardCharsets.UTF_8);
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

    static final String ANNA = "sup-anna";     // a PURE supervisor — member of no team
    static final String DUAL = "sup-dual";     // both a member and a supervisor
    static final String MEMBER = "plain-member";

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;
    @Autowired CatalogHierarchyService hierarchy;

    private UUID memberEmea;   // membership-governed, tagged emea → passes the membership residual
    private UUID memberApac;   // membership-governed, tagged apac → the membership residual REJECTS it
    private UUID supervisedA;  // supervised only, tagged apac → admitted only by the widening arm
    private UUID supervisedB;  // supervised only, tagged apac
    private UUID unreachable;  // neither — the leak row a broken scope would surface

    private ListAppender<ILoggingEvent> auditAppender;
    private ch.qos.logback.classic.Logger auditLogger;

    @BeforeEach
    void seed() {
        catalogs.deleteAll();
        GOVERNED.clear();
        SUPERVISED.clear();

        memberEmea = saveCatalog("member-emea", Map.of("region", "emea"));
        memberApac = saveCatalog("member-apac", Map.of("region", "apac"));
        supervisedA = saveCatalog("supervised-a", Map.of("region", "apac"));
        supervisedB = saveCatalog("supervised-b", Map.of("region", "apac"));
        unreachable = saveCatalog("unreachable", Map.of("region", "emea"));

        GOVERNED.put(DUAL, List.of(memberEmea, memberApac));
        GOVERNED.put(MEMBER, List.of(memberEmea, memberApac));
        // The dual-hatted subject supervises the two supervised catalogs AND one they already govern —
        // memberApac is reachable BOTH ways, so `supervised := S \ M` must drop it from the widening arm.
        SUPERVISED.put(DUAL, List.of(supervisedA, supervisedB, memberApac));
        SUPERVISED.put(ANNA, List.of(supervisedA, supervisedB));

        auditLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger("dev.dmitriikonovalov.example.catalog.audit.SupervisedRead");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void detachAppender() {
        auditLogger.detachAppender(auditAppender);
    }

    private UUID saveCatalog(String name, Map<String, Object> tags) {
        CatalogEntity entity = new CatalogEntity(UUID.randomUUID(), name, name + " description");
        entity.setTags(ResourceTags.fromMap(tags));
        hierarchy.assignPath(entity);
        return catalogs.save(entity).getId();
    }

    private org.springframework.test.web.servlet.ResultActions listAs(String subject, int page, int perPage)
            throws Exception {
        return mockMvc.perform(get("/api/v1/catalogs")
                .header(SUBJECT_HEADER, subject)
                .queryParam("page", String.valueOf(page))
                .queryParam("perPage", String.valueOf(perPage)));
    }

    static final String SUBJECT_HEADER = "X-Test-Subject";

    // --- I4 / U31 — the paged union: no row twice, none skipped at a boundary, exact total ------------

    @Test
    void pagedUnionIsStableAcrossPageBoundaries() throws Exception {
        // The dual-hatted subject's authorized set is exactly {memberEmea} ∪ {supervisedA, supervisedB}:
        // memberApac is a membership row the residual rejects, and it is NOT on the widening arm.
        List<String> firstPage = idsOf(listAs(DUAL, 0, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))   // U31 — the authorized total across BOTH legs
                .andExpect(jsonPath("$.items.length()").value(2)));
        List<String> secondPage = idsOf(listAs(DUAL, 1, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.items.length()").value(1)));

        List<String> all = new ArrayList<>(firstPage);
        all.addAll(secondPage);
        assertThat(all).doesNotHaveDuplicates();            // no row twice at the boundary
        assertThat(all).containsExactlyInAnyOrder(          // none skipped
                memberEmea.toString(), supervisedA.toString(), supervisedB.toString());
        assertThat(all).doesNotContain(unreachable.toString());
        // THE FAIL-OPEN EDGE: memberApac is reachable both ways. Membership wins, so it is judged by the
        // membership residual (which rejects it) and never rides the vacuous supervisor arm.
        assertThat(all).doesNotContain(memberApac.toString());
    }

    @Test // a PURE supervisor (member of no team) gets exactly the supervised set
    void pureSupervisorSeesExactlyTheSupervisedSet() throws Exception {
        List<String> ids = idsOf(listAs(ANNA, 0, 50)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2)));

        assertThat(ids).containsExactlyInAnyOrder(supervisedA.toString(), supervisedB.toString());
    }

    @Test // an ordinary member is unaffected: the membership residual alone decides, byte-identical
    void ordinaryMemberIsUnchanged() throws Exception {
        List<String> ids = idsOf(listAs(MEMBER, 0, 50)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1)));

        assertThat(ids).containsExactly(memberEmea.toString()); // apac still rejected by the residual
    }

    // --- I5 — the read-only ceiling: a supervised row's _actions map ---------------------------------

    @Test
    void supervisedRowActionsArePresentAndReadOnly() throws Exception {
        // The verb set is the shipped CatalogEnrichable registry, verified against the live endpoints:
        // view / update / delete / assign-tags. The map is PRESENT, not omitted — the omit-on-all-false
        // degrade fires only when EVERY verb is false, and `view` is true here.
        listAs(ANNA, 0, 50)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]._actions").exists())
                .andExpect(jsonPath("$.items[0]._actions.view").value(true))
                .andExpect(jsonPath("$.items[0]._actions.update").value(false))
                .andExpect(jsonPath("$.items[0]._actions.delete").value(false))
                .andExpect(jsonPath("$.items[0]._actions.['assign-tags']").value(false));
    }

    // --- I6 — the audit event ------------------------------------------------------------------------

    @Test
    void supervisedListReadEmitsExactlyOneAuditEvent() throws Exception {
        listAs(ANNA, 0, 50).andExpect(status().isOk());

        assertThat(auditAppender.list).hasSize(1);
        ILoggingEvent event = auditAppender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getLoggerName())
                .isEqualTo("dev.dmitriikonovalov.example.catalog.audit.SupervisedRead");
        String rendered = event.getFormattedMessage();
        assertThat(rendered).contains(ANNA);                        // the subject
        assertThat(rendered).contains("accessPath=supervised");     // the access path
        // the supervised root ids AS A LIST — a page can span several roots
        assertThat(rendered).contains(supervisedA.toString()).contains(supervisedB.toString());
    }

    @Test // a MIXED page audits too, and names its access path distinctly
    void mixedPageAuditsWithTheMixedAccessPath() throws Exception {
        listAs(DUAL, 0, 50).andExpect(status().isOk());

        assertThat(auditAppender.list).hasSize(1);
        assertThat(auditAppender.list.get(0).getFormattedMessage()).contains("accessPath=mixed");
    }

    @Test // NO event on an ordinary membership read
    void ordinaryMembershipReadEmitsNoAuditEvent() throws Exception {
        listAs(MEMBER, 0, 50).andExpect(status().isOk());

        assertThat(auditAppender.list).isEmpty();
    }

    @Test // NO event when the supervised leg contributed no row to the page
    void supervisedLegContributingNoRowEmitsNoAuditEvent() throws Exception {
        // Page 0 of a 1-per-page mixed list holds only memberEmea (createdAt order): the supervised leg
        // contributed nothing to THIS page, so nothing is logged.
        listAs(DUAL, 0, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("member-emea"));

        assertThat(auditAppender.list).isEmpty();
    }

    private static List<String> idsOf(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        List<String> ids = new ArrayList<>();
        root.get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    // --- test doubles --------------------------------------------------------------------------------

    @TestConfiguration
    static class SupervisedListTestConfig {

        /** The subject comes from a request header so one context can exercise several personas. */
        @Bean
        AbacSubjectExtractor headerSubjectExtractor() {
            return request -> {
                String subject = request.getHeader(SUBJECT_HEADER);
                return subject == null
                        ? Optional.empty()
                        : Optional.of(new AbacContext.Subject(subject, List.of(), Map.of()));
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

        /**
         * The membership role for a governed id, the synthesized supervisor role for a supervised one —
         * exactly the split T2 ships on {@code /internal/effective-role}.
         */
        @Bean
        RoleDefinitionSupplier supervisedAwareRoleSupplier() {
            return (userId, type, id) -> {
                UUID resourceId = UUID.fromString(id);
                if (GOVERNED.getOrDefault(userId, List.of()).contains(resourceId)) {
                    return Optional.of(new RoleDefinition(
                            "owner",
                            Map.of("provenance", "membership"),
                            Map.of("catalog", List.of("READ", "WRITE", "TAG"))));
                }
                if (SUPERVISED.getOrDefault(userId, List.of()).contains(resourceId)) {
                    return Optional.of(new RoleDefinition(
                            "supervisor-readonly",
                            Map.of("provenance", "supervised"),
                            Map.of("catalog", List.of("READ"))));
                }
                return Optional.empty();
            };
        }

        @Bean
        @Primary
        OpaClient supervisedStubOpaClient() {
            return new SupervisedStubOpaClient();
        }
    }

    /**
     * {@code compile} returns a real DNF ({@code tags.region = 'emea'}) so the membership residual is
     * load-bearing SQL rather than a vacuous ALLOW_ALL; {@code allow}/{@code allowAll} decide the
     * enrichment verbs from the row's role — {@code view} always, mutations only for a membership role.
     */
    static final class SupervisedStubOpaClient implements OpaClient {

        @Override
        public boolean allow(AbacContext context) {
            return decide(context);
        }

        /**
         * Role-aware, mirroring what the real corpus returns (measured with {@code opa eval --partial}
         * against {@code infra/opa/policies}, recorded in {@code STATUS-05.md}):
         * <ul>
         *   <li>a <b>tag-gated membership</b> role → the real DNF {@code tags.region = 'emea'}, so the
         *       membership residual is load-bearing SQL rather than a vacuous ALLOW_ALL;</li>
         *   <li>the <b>synthesized supervisor</b> role → <b>ALLOW_ALL</b>, because it grants the coarse
         *       {@code READ} token with <em>no</em> {@code required_tags}, so {@code data.catalog.filter}
         *       folds to the type-eq tautology. That is U34's precondition, and it is exactly what makes
         *       admitting supervised rows through the {@code subtreeSpec} arm correct.</li>
         * </ul>
         */
        @Override
        public PartialResult compile(AbacContext context) {
            RoleDefinition role = context.roleDefinition();
            if (role != null && "supervised".equals(role.attributes().get("provenance"))) {
                return PartialResult.allowAll();
            }
            return PartialResult.conditional(
                    List.of(new Conjunction(List.of(
                            new Condition("tags.region", Condition.Operator.EQ, "emea")))));
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return contexts.stream().map(SupervisedStubOpaClient::decide).toList();
        }

        /** The shipped semantics in miniature: READ grants view; mutations need a WRITE/TAG token. */
        private static boolean decide(AbacContext context) {
            RoleDefinition role = context.roleDefinition();
            if (role == null) {
                return false;
            }
            List<String> tokens = role.permissions().getOrDefault("catalog", List.of());
            String verb = context.action().substring(context.action().indexOf(':') + 1);
            return switch (verb) {
                case "view", "list" -> tokens.contains("READ");
                case "update", "delete" -> tokens.contains("WRITE");
                case "assign-tags" -> tokens.contains("TAG");
                default -> false;
            };
        }
    }
}
