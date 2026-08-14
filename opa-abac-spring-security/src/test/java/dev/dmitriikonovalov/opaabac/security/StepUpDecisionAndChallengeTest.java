package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.read.ListAppender;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.AbacResourceResolver;
import dev.dmitriikonovalov.opaabac.core.AncestorChainSupplier;
import dev.dmitriikonovalov.opaabac.core.DenyReason;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaDecision;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * U16–U19 — the step-up deny travelling from the policy to the wire: the manager's decision shapes, the
 * advice's 401/403 matrix, the half-formed-challenge guard, and the two audit events.
 *
 * <p>The negative cases carry the weight. A challenge is a promise that re-authenticating clears the
 * deny, so it must be emitted <em>only</em> for a complete reason, and the ordinary 403 must stay
 * byte-identical for everything else.
 */
class StepUpDecisionAndChallengeTest {

    private static final UUID CATEGORY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CATALOG_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final DenyReason COMPLETE =
            new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, "aal2", 300);

    private final OpaClient opaClient = mock(OpaClient.class);
    private final RoleDefinitionSupplier roleDefinitionSupplier = mock(RoleDefinitionSupplier.class);
    private final AbacResourceResolver resolver = mock(AbacResourceResolver.class);
    private final AncestorChainSupplier chainSupplier = mock(AncestorChainSupplier.class);
    private final MapCache cache = new MapCache();

    private final Supplier<Authentication> noopAuthSupplier = () -> null;
    private ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> auditAppender;

    @BeforeEach
    void setUp() {
        authenticateAs(Map.of("acr", "aal2", "auth_time", 1786000000));
        lenient().when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        auditAppender = attachTo("opa.abac.audit");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("opa.abac.audit"))
                .detachAppender(auditAppender);
    }

    private static void authenticateAs(Map<String, Object> attributes) {
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(
                new AbacContext.Subject("sup-anna", List.of(), attributes)));
    }

    private static ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> attachTo(String loggerName) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger(loggerName);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        return appender;
    }

    private String auditLine(String event) {
        return auditAppender.list.stream()
                .map(e -> e.getFormattedMessage())
                .filter(m -> m.contains("event=" + event))
                .findFirst()
                .orElse(null);
    }

    // --- the target + fixtures ----------------------------------------------------------

    // A pointcut target only: the gate decides before the body runs, which is why there is none.
    @SuppressWarnings({"unused", "java:S1186"})
    static class SampleController {
        @OpaPreAuthorize(action = "category:view", resourceType = "'category'", resourceId = "#id")
        public void viewCategory(UUID id) {}
    }

    private MethodInvocation invocation() throws Exception {
        SampleController target = new SampleController();
        Method method = SampleController.class.getMethod("viewCategory", UUID.class);
        MethodInvocation invocation = mock(MethodInvocation.class);
        lenient().when(invocation.getMethod()).thenReturn(method);
        lenient().when(invocation.getThis()).thenReturn(target);
        lenient().when(invocation.getArguments()).thenReturn(new Object[] {CATEGORY_ID});
        return invocation;
    }

    private record Node(String type, String id, Map<String, Object> attributes) implements AbacResource {
        @Override public String abacResourceType() { return type; }
        @Override public String abacResourceId() { return id; }
        @Override public Map<String, Object> abacAttributes() { return attributes; }
    }

    private static final class MapCache implements AbacResourceCache {
        private final Map<String, Object> entries = new HashMap<>();

        @Override public <T> Optional<T> get(String type, String id, Class<T> as) {
            return Optional.ofNullable(entries.get(type + "/" + id)).map(as::cast);
        }

        @Override public void put(String type, String id, Object resource) {
            entries.put(type + "/" + id, resource);
        }
    }

    /**
     * The manager wired with resolution support so the governing root (and its env tag) is real.
     *
     * <p>The cache is cleared per call on purpose: the root memo is <em>decision-independent</em>, so a
     * case that re-tags the same root within one test method would otherwise read the previous tag back
     * out of the memo rather than from the resolver.
     */
    private OpaPreAuthorizeAuthorizationManager managerWithRoot(Map<String, Object> rootAttributes) {
        cache.entries.clear();
        lenient().when(resolver.resolve("category", CATEGORY_ID.toString()))
                .thenReturn(Optional.of(new Node("category", CATEGORY_ID.toString(), Map.of())));
        lenient().when(resolver.resolve("catalog", CATALOG_ID.toString()))
                .thenReturn(Optional.of(new Node("catalog", CATALOG_ID.toString(), rootAttributes)));
        lenient().when(chainSupplier.ancestorsOf("category", CATEGORY_ID.toString()))
                .thenReturn(List.of(new ParentRef("catalog", CATALOG_ID.toString())));
        // The example's vocabulary, passed explicitly — the library no longer hardcodes it (ADR 0030
        // §8 Amendment 7). A manager built WITHOUT a policy emits no privileged-read event at all,
        // which auditIsSilentWithoutAPolicy() pins.
        return new OpaPreAuthorizeAuthorizationManager(opaClient, roleDefinitionSupplier,
                new ResourceResolutionSupport(resolver, chainSupplier, cache), EXAMPLE_AUDIT_POLICY);
    }

    /** This repo's example vocabulary — the adopter's, not the library's. */
    private static final PrivilegedReadAuditPolicy EXAMPLE_AUDIT_POLICY =
            new PrivilegedReadAuditPolicy("supervised", "env", java.util.Set.of("production"));

    private static RoleDefinition supervisorRole() {
        return new RoleDefinition("supervisor-readonly", Map.of("provenance", "supervised"),
                Map.of("category", List.of("READ")));
    }

    // --- U16: the manager's decision shapes ---------------------------------------------

    @Test // a reason on the wire → StepUpRequiredDecision, denied, carrying the log-only coordinates
    void managerSurfacesTheStructuredDeny() throws Exception {
        when(opaClient.decide(any())).thenReturn(new OpaDecision(false, COMPLETE));

        AuthorizationDecision decision =
                managerWithRoot(Map.of("env", "production")).authorize(noopAuthSupplier, invocation());

        assertThat(decision).isInstanceOf(StepUpRequiredDecision.class);
        assertThat(decision.isGranted()).isFalse();
        StepUpRequiredDecision stepUp = (StepUpRequiredDecision) decision;
        assertThat(stepUp.reason()).isEqualTo(COMPLETE);
        assertThat(stepUp.resourceType()).isEqualTo("category");
        assertThat(stepUp.resourceId()).isEqualTo(CATEGORY_ID.toString());
        assertThat(stepUp.governingRootId()).isEqualTo(CATALOG_ID.toString());
    }

    @Test // a plain deny → today's plain AuthorizationDecision, NOT the step-up subclass
    void managerKeepsThePlainDenyShape() throws Exception {
        when(opaClient.decide(any())).thenReturn(OpaDecision.deny());

        AuthorizationDecision decision =
                managerWithRoot(Map.of("env", "production")).authorize(noopAuthSupplier, invocation());

        assertThat(decision.isGranted()).isFalse();
        assertThat(decision).isNotInstanceOf(StepUpRequiredDecision.class);
    }

    @Test // an allow → granted, and never a step-up shape even if a reason were somehow attached
    void managerKeepsTheAllowShape() throws Exception {
        when(opaClient.decide(any())).thenReturn(new OpaDecision(true, COMPLETE));

        AuthorizationDecision decision =
                managerWithRoot(Map.of("env", "staging")).authorize(noopAuthSupplier, invocation());

        assertThat(decision.isGranted()).isTrue();
        assertThat(decision).isNotInstanceOf(StepUpRequiredDecision.class);
    }

    @Test // a client breaking the never-null contract denies explicitly, not by NPE
    void managerDeniesOnANullDecision() throws Exception {
        when(opaClient.decide(any())).thenReturn(null);

        AuthorizationDecision decision =
                managerWithRoot(Map.of("env", "production")).authorize(noopAuthSupplier, invocation());

        assertThat(decision.isGranted()).isFalse();
        assertThat(decision).isNotInstanceOf(StepUpRequiredDecision.class);
    }

    // --- U19 (manager half): PRIVILEGED_READ ----------------------------------

    @Test // granted + supervised + a production root → the read event, with acr/auth_time verbatim
    void auditsTheSupervisedProductionRead() throws Exception {
        when(opaClient.decide(any())).thenReturn(OpaDecision.permit());
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.of(supervisorRole()));

        managerWithRoot(Map.of("env", "production")).authorize(noopAuthSupplier, invocation());

        assertThat(auditLine("PRIVILEGED_READ"))
                .isNotNull()
                .contains("subject=sup-anna", "accessPath=supervised",
                        "governingRootId=" + CATALOG_ID, "resourceType=category",
                        "resourceId=" + CATEGORY_ID, "acr=aal2", "authTime=1786000000");
    }

    @Test // ADR 0030 §8 Amendment 7: UNCONFIGURED means SILENT — a published starter must not fire an
    void auditIsSilentWithoutAPolicy() throws Exception { // event keyed on this repo's example nouns.
        when(opaClient.decide(any())).thenReturn(OpaDecision.permit());
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.of(supervisorRole()));

        // The very input that DOES audit above, on a manager built without a policy (the 3-arg
        // constructor every pre-C adopter already uses).
        cache.entries.clear();
        lenient().when(resolver.resolve("category", CATEGORY_ID.toString()))
                .thenReturn(Optional.of(new Node("category", CATEGORY_ID.toString(), Map.of())));
        lenient().when(resolver.resolve("catalog", CATALOG_ID.toString()))
                .thenReturn(Optional.of(new Node("catalog", CATALOG_ID.toString(),
                        Map.of("env", "production"))));
        lenient().when(chainSupplier.ancestorsOf("category", CATEGORY_ID.toString()))
                .thenReturn(List.of(new ParentRef("catalog", CATALOG_ID.toString())));

        new OpaPreAuthorizeAuthorizationManager(opaClient, roleDefinitionSupplier,
                new ResourceResolutionSupport(resolver, chainSupplier, cache))
                .authorize(noopAuthSupplier, invocation());

        assertThat(auditLine("PRIVILEGED_READ")).isNull();
    }

    @Test // …and a DIFFERENT adopter vocabulary works: the trigger follows the configured nouns
    void auditFollowsTheConfiguredVocabulary() throws Exception {
        when(opaClient.decide(any())).thenReturn(OpaDecision.permit());
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.of(
                new RoleDefinition("auditor", Map.of("provenance", "oversight"),
                        Map.of("category", List.of("READ")))));

        cache.entries.clear();
        lenient().when(resolver.resolve("category", CATEGORY_ID.toString()))
                .thenReturn(Optional.of(new Node("category", CATEGORY_ID.toString(), Map.of())));
        lenient().when(resolver.resolve("catalog", CATALOG_ID.toString()))
                .thenReturn(Optional.of(new Node("catalog", CATALOG_ID.toString(),
                        Map.of("classification", List.of("restricted", "internal")))));
        lenient().when(chainSupplier.ancestorsOf("category", CATEGORY_ID.toString()))
                .thenReturn(List.of(new ParentRef("catalog", CATALOG_ID.toString())));

        new OpaPreAuthorizeAuthorizationManager(opaClient, roleDefinitionSupplier,
                new ResourceResolutionSupport(resolver, chainSupplier, cache),
                new PrivilegedReadAuditPolicy("oversight", "classification",
                        java.util.Set.of("restricted")))
                .authorize(noopAuthSupplier, invocation());

        assertThat(auditLine("PRIVILEGED_READ"))
                .isNotNull()
                .contains("accessPath=oversight");
    }

    @Test // an ARRAY-shaped env is production too — the cardinality twin of the policy's root_env_values
    void auditsAnArrayShapedProductionTier() throws Exception {
        when(opaClient.decide(any())).thenReturn(OpaDecision.permit());
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.of(supervisorRole()));

        managerWithRoot(Map.of("env", List.of("production", "staging")))
                .authorize(noopAuthSupplier, invocation());

        assertThat(auditLine("PRIVILEGED_READ")).isNotNull();
    }

    @Test // the three ways the event must NOT fire: a member, a non-production tier, a denied read
    void doesNotAuditWhatIsNotAPrivilegedRead() throws Exception {
        when(opaClient.decide(any())).thenReturn(OpaDecision.permit());
        RoleDefinition member = new RoleDefinition("catalog-owner", Map.of("provenance", "membership"),
                Map.of("category", List.of("READ")));
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.of(member));
        managerWithRoot(Map.of("env", "production")).authorize(noopAuthSupplier, invocation());
        assertThat(auditLine("PRIVILEGED_READ")).as("a member's read").isNull();

        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.of(supervisorRole()));
        managerWithRoot(Map.of("env", "staging")).authorize(noopAuthSupplier, invocation());
        assertThat(auditLine("PRIVILEGED_READ")).as("a staging tier").isNull();

        when(opaClient.decide(any())).thenReturn(OpaDecision.deny());
        managerWithRoot(Map.of("env", "production")).authorize(noopAuthSupplier, invocation());
        assertThat(auditLine("PRIVILEGED_READ")).as("a denied read").isNull();
    }

    @Test // an exception INSIDE emission is swallowed — an audit bug is never an authorization outage
    void auditEmissionSwallowsItsOwnFailure() {
        // Driven straight at the emitter: AbacContext.Subject defensively copies its attribute map, so a
        // hostile map cannot reach the payload through the manager. The guarantee under test is the
        // emitter's, and this is where it lives.
        org.assertj.core.api.Assertions.assertThatCode(() -> AbacAuditLogger.privilegedRead(
                        "sup-anna", new HostileMap(), "category", "k-1", "c-1", "supervised"))
                .doesNotThrowAnyException();

        assertThat(auditLine("PRIVILEGED_READ")).isNull();
    }

    /** An attribute map that blows up when the audit payload reads it. */
    private static final class HostileMap extends java.util.AbstractMap<String, Object> {
        @Override public java.util.Set<Entry<String, Object>> entrySet() {
            return java.util.Set.of();
        }

        @Override public Object get(Object key) {
            throw new IllegalStateException("attribute access exploded");
        }
    }

    // --- U17/U18/U19 (advice half): the 401/403 matrix -----------------------------------

    /** The concrete advice a service would extend — nothing overridden, so this is the shipped behaviour. */
    private static final class TestAdvice extends AbstractProblemAdvice {}

    private static ResponseEntity<ProblemDetail> render(AuthorizationDecision result) {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/v1/catalogs/c/categories/k");
        return new TestAdvice().handleAccessDenied(
                new AuthorizationDeniedException("Access Denied", result), request);
    }

    @Test // U17 — a complete reason → 401 + the RFC 9470 challenge + the STEP_UP_REQUIRED body
    void completeReasonRendersTheChallenge() {
        ResponseEntity<ProblemDetail> response = render(
                new StepUpRequiredDecision(COMPLETE, "category", "k-1", "c-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"insufficient_user_authentication\", "
                        + "error_description=\"Re-authentication with a stronger, fresher factor is required\", "
                        + "acr_values=\"aal2\", max_age=\"300\"");
        assertThat(response.getBody().errorCode()).isEqualTo("STEP_UP_REQUIRED");
    }

    @Test // …and the challenge parameters come FROM THE REASON, never from a local copy
    void challengeParametersComeFromTheReason() {
        ResponseEntity<ProblemDetail> response = render(new StepUpRequiredDecision(
                new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, "aal3", 60),
                "category", "k-1", "c-1"));

        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .contains("acr_values=\"aal3\"", "max_age=\"60\"");
    }

    @Test // U17 — an ordinary denial is byte-identical to pre-C: 403, no challenge header
    void plainDenialKeepsTheExistingForbidden() {
        ResponseEntity<ProblemDetail> fromDecision = render(new AuthorizationDecision(false));
        assertThat(fromDecision.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fromDecision.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(fromDecision.getBody().errorCode()).isEqualTo("ACCESS_DENIED");

        ResponseEntity<ProblemDetail> fromAccessDenied = new TestAdvice().handleAccessDenied(
                new AccessDeniedException("nope"), new MockHttpServletRequest("GET", "/v1/catalogs"));
        assertThat(fromAccessDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fromAccessDenied.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    @Test // U18 — ANY null field → the plain 403. A challenge without its window is the §7 loop.
    void partialReasonFallsBackToForbidden() {
        List<DenyReason> partial = List.of(
                new DenyReason(null, "aal2", 300),
                new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, null, 300),
                new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, "aal2", null));

        for (DenyReason reason : partial) {
            ResponseEntity<ProblemDetail> response =
                    render(new StepUpRequiredDecision(reason, "category", "k-1", "c-1"));
            assertThat(response.getStatusCode()).as("reason: %s", reason).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
            assertThat(response.getBody().errorCode()).isEqualTo("ACCESS_DENIED");
        }
    }

    @Test // the description is the ADOPTER's: an override reaches BOTH the header and the body
    void challengeDescriptionFollowsTheOverride() {
        final class DomainAdvice extends AbstractProblemAdvice {
            @Override
            protected String stepUpChallengeDescription() {
                return "Confirm your identity to approve a payment";
            }
        }
        HttpServletRequest request = new MockHttpServletRequest("GET", "/v1/payments/p-1");

        ResponseEntity<ProblemDetail> response = new DomainAdvice().handleAccessDenied(
                new AuthorizationDeniedException("Access Denied",
                        new StepUpRequiredDecision(COMPLETE, "payment", "p-1", "acct-1")),
                request);

        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .contains("error_description=\"Confirm your identity to approve a payment\"")
                .doesNotContain("production");
        assertThat(response.getBody().detail()).isEqualTo("Confirm your identity to approve a payment");
    }

    @Test // an override that cannot be safely quoted suppresses the challenge, like any other parameter
    void unquotableDescriptionFallsBackToForbidden() {
        final class HostileAdvice extends AbstractProblemAdvice {
            @Override
            protected String stepUpChallengeDescription() {
                return "oops\", scope=\"admin";
            }
        }
        ResponseEntity<ProblemDetail> response = new HostileAdvice().handleAccessDenied(
                new AuthorizationDeniedException("Access Denied",
                        new StepUpRequiredDecision(COMPLETE, "category", "k-1", "c-1")),
                new MockHttpServletRequest("GET", "/v1/catalogs/c/categories/k"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    @Test // a NEGATIVE window → the plain 403: no re-authentication can land inside it
    void negativeMaxAgeFallsBackToForbidden() {
        ResponseEntity<ProblemDetail> response = render(new StepUpRequiredDecision(
                new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, "aal2", -1),
                "category", "k-1", "c-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    @Test // …but a ZERO window still challenges: the policy enforces max_age + SKEW, and the reason
    void zeroMaxAgeStillChallenges() { // carries only max_age — muting it would withhold the remedy.
        ResponseEntity<ProblemDetail> response = render(new StepUpRequiredDecision(
                new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, "aal2", 0),
                "category", "k-1", "c-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .contains("max_age=\"0\"");
    }

    @Test // an unrecognized reason type → the plain 403. A 401 whose error code no client can act on
    void unknownReasonTypeFallsBackToForbidden() { // is the §7 loop wearing a different hat.
        List<DenyReason> unknown = List.of(
                new DenyReason("insufficient_wizardry", "aal2", 300),
                new DenyReason("Insufficient_User_Authentication", "aal2", 300)); // case is significant

        for (DenyReason reason : unknown) {
            ResponseEntity<ProblemDetail> response =
                    render(new StepUpRequiredDecision(reason, "category", "k-1", "c-1"));
            assertThat(response.getStatusCode()).as("reason: %s", reason).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
            assertThat(response.getBody().errorCode()).isEqualTo("ACCESS_DENIED");
        }
    }

    @Test // a parameter that cannot be safely quoted → the plain 403, never a spliced header
    void unquotableParametersFallBackToForbidden() {
        List<DenyReason> hostile = List.of(
                new DenyReason("insufficient\"_user_authentication", "aal2", 300),
                new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, "aal2\", scope=\"admin", 300),
                new DenyReason(DenyReason.INSUFFICIENT_USER_AUTHENTICATION, "aal2\r\nX-Evil: 1", 300));

        for (DenyReason reason : hostile) {
            ResponseEntity<ProblemDetail> response =
                    render(new StepUpRequiredDecision(reason, "category", "k-1", "c-1"));
            assertThat(response.getStatusCode()).as("reason: %s", reason).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        }
    }

    @Test // U19 (advice half) — STEP_UP_CHALLENGED, with NO acr/auth_time (the subject is not elevated)
    void auditsTheChallenge() {
        authenticateAs(Map.of("acr", "aal1", "auth_time", 1786000000));

        render(new StepUpRequiredDecision(COMPLETE, "category", "k-1", "c-1"));

        assertThat(auditLine("STEP_UP_CHALLENGED"))
                .isNotNull()
                .contains("subject=sup-anna", "resourceType=category", "resourceId=k-1",
                        "governingRootId=c-1", "requiredAcr=aal2", "maxAge=300")
                .doesNotContain("acr=aal1", "authTime=");
    }

    @Test // no event when no challenge is minted — a plain 403 is not a challenge
    void doesNotAuditAPlainDenial() {
        render(new AuthorizationDecision(false));
        render(new StepUpRequiredDecision(new DenyReason("t", "aal2", null), "category", "k-1", "c-1"));

        assertThat(auditLine("STEP_UP_CHALLENGED")).isNull();
    }
}
