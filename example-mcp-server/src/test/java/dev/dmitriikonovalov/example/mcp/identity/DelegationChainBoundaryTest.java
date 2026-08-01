package dev.dmitriikonovalov.example.mcp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.security.JwtClaimsSubjectExtractor;
import dev.dmitriikonovalov.opaabac.security.SubjectClaimsConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * I4/I5 at the real boundary: a token goes through the <strong>shipped</strong> subject extractor and the
 * resulting subject through ours, so the whole claim path is exercised rather than just the parsing.
 *
 * <p>This is the test that matters most for the design choice behind {@link DelegationChainExtractor}.
 * Reading the actor claim out of {@code Subject#attributes()} rather than re-parsing the token keeps one
 * place deciding what a token means — but it makes the claim's arrival depend on
 * {@code opa.abac.subject.attribute-claims}. Here that dependency is exercised end to end; and
 * {@link ActorClaimWiringCheck} is what stops it from ever being misconfigured in production.
 */
class DelegationChainBoundaryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IdentityProperties properties = new IdentityProperties();
    private final ClaimDelegationChainExtractor delegation =
            new ClaimDelegationChainExtractor(MAPPER, properties);
    private final JwtClaimsSubjectExtractor subjects = new JwtClaimsSubjectExtractor(
            MAPPER,
            new SubjectClaimsConfig(
                    "sub", "realm_access.roles", "preferred_username", List.of("act_chain"), true));

    /** A gateway-forwarded token: three segments, only the payload is ever read. */
    private static String token(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".not-verified-here";
    }

    private static String payload(String sub, String actChainJson) {
        long exp = Instant.now().plusSeconds(300).getEpochSecond();
        String act = actChainJson == null ? "" : ",\"act_chain\":" + actChainJson;
        return "{\"sub\":\"" + sub + "\",\"exp\":" + exp
                + ",\"realm_access\":{\"roles\":[\"catalog-reader\"]}" + act + "}";
    }

    private DelegationChain chainFor(String payloadJson) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token(payloadJson));
        Optional<AbacContext.Subject> subject = subjects.extract(request);
        assertThat(subject).as("the shipped extractor must resolve a subject").isPresent();
        return delegation.extract(subject.get());
    }

    @Test // I4 — the two paths are distinguishable at the boundary
    void separatesAnAgentTokenFromAPlainTokenEndToEnd() {
        DelegationChain agent = chainFor(payload("user-alice", "[\"agent-a\"]"));
        DelegationChain human = chainFor(payload("user-alice", null));

        assertThat(agent.isAgentCall()).isTrue();
        assertThat(agent.principal()).isEqualTo("user-alice");
        assertThat(agent.actor()).isEqualTo("agent-a");
        assertThat(agent.chain()).containsExactly("agent-a");

        assertThat(human.isAgentCall()).isFalse();
        assertThat(human.principal()).isEqualTo("user-alice");
        assertThat(human.chain()).isEmpty();
    }

    @Test // I4 — the nested RFC 8693 shape survives the same round trip
    void readsANestedActClaimThroughTheShippedExtractor() {
        DelegationChain chain = chainFor(
                payload("user-alice", "{\"sub\":\"agent-a\",\"act\":{\"sub\":\"agent-b\"}}"));

        assertThat(chain.chain()).containsExactly("agent-a", "agent-b");
    }

    @Test // I5 — derived per request; nothing is carried between them
    void derivesAFreshChainForEveryRequest() {
        DelegationChain first = chainFor(payload("user-alice", "[\"agent-a\"]"));
        DelegationChain second = chainFor(payload("user-bob", "[\"agent-b\"]"));
        DelegationChain third = chainFor(payload("user-carol", null));

        assertThat(first.principal()).isEqualTo("user-alice");
        assertThat(first.actor()).isEqualTo("agent-a");
        assertThat(second.principal()).isEqualTo("user-bob");
        assertThat(second.actor()).isEqualTo("agent-b");
        assertThat(third.principal()).isEqualTo("user-carol");
        assertThat(third.actor()).isNull();
        assertThat(first).isNotEqualTo(second);
    }

    @Test // I4 — a malformed claim still denies once it has crossed the real extractor
    void deniesAMalformedClaimAtTheBoundary() {
        assertThatThrownBy(() -> chainFor(payload("user-alice", "\"agent-a\"")))
                .isInstanceOf(DelegationChainException.class);
        assertThatThrownBy(() -> chainFor(payload("user-alice", "[\"user-alice\"]")))
                .isInstanceOf(DelegationChainException.class);
    }

    @Test // the config coupling, demonstrated: an un-copied claim reads as a human call
    void showsWhyTheWiringCheckExists() {
        JwtClaimsSubjectExtractor withoutTheClaim = new JwtClaimsSubjectExtractor(
                MAPPER,
                new SubjectClaimsConfig(
                        "sub", "realm_access.roles", "preferred_username", List.of(), true));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token(payload("user-alice", "[\"agent-a\"]")));
        AbacContext.Subject subject = withoutTheClaim.extract(request).orElseThrow();

        // The token really did carry an actor, but the claim never reached the subject — so the
        // extractor honestly reports a human call. Silent, total loss of agent narrowing. This is
        // exactly what ActorClaimWiringCheck refuses to let a deployment do.
        assertThat(delegation.extract(subject).isAgentCall()).isFalse();
    }
}
