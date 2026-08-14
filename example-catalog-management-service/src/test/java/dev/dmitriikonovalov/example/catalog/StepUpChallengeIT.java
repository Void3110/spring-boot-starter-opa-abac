package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.DenyReason;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaDecision;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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
 * STEP-UP-ELEVATION <b>T4</b> — the challenge on the wire, below the rig: real Postgres (Testcontainers,
 * never H2) and a programmable OPA stub. QA cases <b>I1, I2, I4</b>.
 *
 * <h2>What this proves that a unit test cannot</h2>
 * That the reason survives the whole path: OPA's response → {@code OpaClient.decide} → the manager's
 * {@code StepUpRequiredDecision} → Spring Security's {@code AuthorizationDeniedException} → the shared
 * advice → an actual HTTP response with a header and a problem body. Every link in that chain is a place
 * the reason could be dropped silently, and only an end-to-end request exercises all of them at once.
 *
 * <p>The contrast case matters as much as the challenge: a deny with <em>no</em> reason must come back
 * byte-identical to what slice B shipped — 403, {@code ACCESS_DENIED}, no {@code WWW-Authenticate}. The
 * two must stay distinguishable on the wire, which is the whole of ADR 0030 §7's fingerprinting stance.
 */
@SpringBootTest(properties = {
    "catalog.role-source=none",
    "opa.abac.action-enrichment.enabled=false"
})
@Testcontainers
@AutoConfigureMockMvc
@Import(StepUpChallengeIT.StepUpTestConfig.class)
class StepUpChallengeIT {

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

    private static final String SUBJECT_HEADER = "X-Test-Subject";
    private static final String SUPERVISOR = "sup-anna";
    private static final DenyReason COMPLETE_REASON =
            new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, "aal2", 300);

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;
    @Autowired CategoryRepository categories;
    @Autowired CatalogHierarchyService hierarchy;

    private ListAppender<ILoggingEvent> auditAppender;

    @BeforeEach
    void reset() {
        categories.deleteAll();
        catalogs.deleteAll();
        ProgrammableOpaClient.answer.set(OpaDecision.permit());
        ProgrammableOpaClient.seen.clear();
        auditAppender = attachTo();
    }

    @AfterEach
    void detach() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("opa.abac.audit"))
                .detachAppender(auditAppender);
    }

    private static ListAppender<ILoggingEvent> attachTo() {
        ch.qos.logback.classic.LoggerContext context =
                (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger("opa.abac.audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        return appender;
    }

    private String auditLine(String event) {
        return auditAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("event=" + event))
                .findFirst()
                .orElse(null);
    }

    /** A production-tagged catalog with one category under it — the shape the tier gates. */
    private Fixture seedTree() {
        var catalog = new CatalogEntity(UUID.randomUUID(), "prod-catalog", "the production catalog");
        catalog.setTags(ResourceTags.fromMap(Map.of("env", "production")));
        hierarchy.assignPath(catalog);
        catalog = catalogs.save(catalog);

        var category = new CategoryEntity(
                UUID.randomUUID(), catalog.getId(), null, "step-up-contents", null);
        hierarchy.assignPath(category);
        category = categories.save(category);

        return new Fixture(catalog.getId(), category.getId());
    }

    private record Fixture(UUID catalogId, UUID categoryId) {}

    // --- I1: the 401 challenge over HTTP -------------------------------------------------

    @Test // OPA answers deny + a complete reason → 401, the RFC 9470 header, the STEP_UP_REQUIRED body
    void aStructuredDenyBecomesA401Challenge() throws Exception {
        Fixture fixture = seedTree();
        ProgrammableOpaClient.answer.set(new OpaDecision(false, COMPLETE_REASON));

        var response = mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{k}",
                        fixture.catalogId(), fixture.categoryId())
                        .header(SUBJECT_HEADER, SUPERVISOR))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("STEP_UP_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401))
                .andReturn().getResponse();

        assertThat(response.getHeader("WWW-Authenticate"))
                .as("the challenge parameters echo the STUB's reason, not a local copy")
                .isEqualTo("Bearer error=\"insufficient_user_authentication\", "
                        + "error_description=\"A second factor is required to read production content\", "
                        + "acr_values=\"aal2\", max_age=\"300\"");
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    @Test // the parameters track the reason: a different window on the wire is a different challenge
    void theChallengeCarriesWhateverTheReasonSaid() throws Exception {
        Fixture fixture = seedTree();
        ProgrammableOpaClient.answer.set(new OpaDecision(false,
                new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, "aal2", 60)));

        var response = mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{k}",
                        fixture.catalogId(), fixture.categoryId())
                        .header(SUBJECT_HEADER, SUPERVISOR))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse();

        assertThat(response.getHeader("WWW-Authenticate")).contains("max_age=\"60\"");
    }

    // --- I2: the plain-deny contrast -----------------------------------------------------

    @Test // no reason → the pre-C 403 exactly: ACCESS_DENIED, and no challenge header at all
    void aPlainDenyIsByteIdenticalToPreC() throws Exception {
        Fixture fixture = seedTree();
        ProgrammableOpaClient.answer.set(OpaDecision.deny());

        var response = mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{k}",
                        fixture.catalogId(), fixture.categoryId())
                        .header(SUBJECT_HEADER, SUPERVISOR))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
                .andReturn().getResponse();

        assertThat(response.getHeader("WWW-Authenticate")).isNull();
    }

    @Test // a PARTIAL reason is not a challenge either — the §7 loop guard, over real HTTP
    void aPartialReasonFallsBackToTheOrdinaryForbidden() throws Exception {
        Fixture fixture = seedTree();
        ProgrammableOpaClient.answer.set(new OpaDecision(false,
                new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, "aal2", null)));

        var response = mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{k}",
                        fixture.catalogId(), fixture.categoryId())
                        .header(SUBJECT_HEADER, SUPERVISOR))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
                .andReturn().getResponse();

        assertThat(response.getHeader("WWW-Authenticate")).isNull();
    }

    // --- I4: both audit events on the wire path ------------------------------------------

    @Test // the I1 flow emits STEP_UP_CHALLENGED, with the challenge fields and no elevation claims
    void theChallengeFlowEmitsItsAuditEvent() throws Exception {
        Fixture fixture = seedTree();
        ProgrammableOpaClient.answer.set(new OpaDecision(false, COMPLETE_REASON));

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{k}",
                        fixture.catalogId(), fixture.categoryId())
                .header(SUBJECT_HEADER, SUPERVISOR)).andExpect(status().isUnauthorized());

        assertThat(auditLine("STEP_UP_CHALLENGED"))
                .isNotNull()
                .contains("subject=" + SUPERVISOR, "resourceType=category",
                        "resourceId=" + fixture.categoryId(), "governingRootId=" + fixture.catalogId(),
                        "requiredAcr=aal2", "maxAge=300")
                .as("the subject is precisely NOT elevated here")
                .doesNotContain("authTime=");
    }

    @Test // an ELEVATED allow emits PRIVILEGED_READ with the claims logged verbatim
    void theElevatedReadEmitsItsAuditEvent() throws Exception {
        Fixture fixture = seedTree();
        ProgrammableOpaClient.answer.set(OpaDecision.permit());

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{k}",
                        fixture.catalogId(), fixture.categoryId())
                .header(SUBJECT_HEADER, SUPERVISOR)).andExpect(status().isOk());

        assertThat(auditLine("PRIVILEGED_READ"))
                .isNotNull()
                .contains("subject=" + SUPERVISOR, "accessPath=supervised",
                        "governingRootId=" + fixture.catalogId(), "resourceType=category",
                        "resourceId=" + fixture.categoryId(), "acr=aal2", "authTime=1786000000");
    }

    @Test // the input the manager actually sent carries the claims the policy needs
    void theDecisionInputCarriesTheElevationClaims() throws Exception {
        Fixture fixture = seedTree();

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{k}",
                        fixture.catalogId(), fixture.categoryId())
                .header(SUBJECT_HEADER, SUPERVISOR)).andExpect(status().isOk());

        AbacContext context = ProgrammableOpaClient.seen.stream()
                .filter(c -> "category:view".equals(c.action()))
                .findFirst().orElseThrow();
        assertThat(context.subject().attributes())
                .containsEntry("acr", "aal2")
                .containsEntry("auth_time", 1786000000);
        assertThat(context.resource().rootAttributes()).isEqualTo(Map.of("env", "production"));
    }

    // --- test doubles --------------------------------------------------------------------

    @TestConfiguration
    static class StepUpTestConfig {

        /**
         * The persona comes from a header, and carries the two elevation claims the catalog service's
         * {@code attribute-claims} config would copy off a real token — {@code auth_time} <b>numeric</b>,
         * because the policy does arithmetic on it.
         */
        @Bean
        AbacSubjectExtractor stepUpSubjectExtractor() {
            return request -> {
                String subject = request.getHeader(SUBJECT_HEADER);
                return subject == null
                        ? Optional.empty()
                        : Optional.of(new AbacContext.Subject(subject, List.of(),
                                Map.of("acr", "aal2", "auth_time", 1786000000)));
            };
        }

        @Bean
        RoleDefinitionSupplier stepUpRoleSupplier() {
            return (userId, type, id) -> Optional.of(new RoleDefinition(
                    "supervisor-readonly",
                    Map.of("provenance", "supervised"),
                    Map.of("catalog", List.of("READ"), "category", List.of("READ"),
                            "product", List.of("READ"))));
        }

        @Bean
        @Primary
        OpaClient programmableOpaClient() {
            return new ProgrammableOpaClient();
        }
    }

    /**
     * A programmable stub: each test sets the decision OPA would answer, so the wire shapes of ADR 0030 §6
     * can be exercised one at a time. It answers through {@code decide} — which is precisely the seam
     * under test.
     */
    static final class ProgrammableOpaClient implements OpaClient {

        static final AtomicReference<OpaDecision> answer = new AtomicReference<>(OpaDecision.permit());
        static final List<AbacContext> seen = new CopyOnWriteArrayList<>();

        @Override
        public boolean allow(AbacContext context) {
            return decide(context).allow();
        }

        @Override
        public OpaDecision decide(AbacContext context) {
            seen.add(context);
            return answer.get();
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.allowAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return contexts.stream().map(c -> answer.get().allow()).toList();
        }
    }
}
