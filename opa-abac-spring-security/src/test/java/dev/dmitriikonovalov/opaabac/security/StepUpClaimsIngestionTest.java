package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * U20 — the three step-up claims reach {@code input.subject.attributes} as <strong>pure
 * configuration</strong>, type-preserved. This slice ships no ingestion code; it ships a list of claim
 * names in the catalog service's yaml. What this pins is that the existing seam actually carries them,
 * and carries them in the shapes the policy needs.
 *
 * <p><strong>The type is the point.</strong> The policy does arithmetic on {@code auth_time}
 * ({@code now - auth_time <= max_age + skew}), and a JSON number arriving as a String would leave
 * {@code elevated} undefined — fail-closed, but silently un-elevatable, which is a bug that looks
 * exactly like correct behaviour. And {@code act_chain} is discriminated by <em>key presence</em>, so an
 * absent claim must be an absent key rather than a null entry.
 */
class StepUpClaimsIngestionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Exactly what the catalog service's application.yml declares. */
    private final JwtClaimsSubjectExtractor extractor = new JwtClaimsSubjectExtractor(MAPPER,
            new SubjectClaimsConfig("sub", "realm_access.roles", "preferred_username",
                    List.of("acr", "auth_time", "act_chain"), true));

    private static String b64url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> attributesOf(String claims) {
        String token = b64url("{\"alg\":\"RS256\"}") + "." + b64url(claims) + ".not-verified";
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        return extractor.extract(request).map(AbacContext.Subject::attributes).orElseThrow();
    }

    private static long soon() {
        return Instant.now().plusSeconds(3600).getEpochSecond();
    }

    @Test // a human, elevated token: acr a String, auth_time NUMERIC, no act_chain key at all
    void anElevatedHumanTokenCarriesAcrAndNumericAuthTime() {
        Map<String, Object> attributes = attributesOf(
                "{\"sub\":\"anna\",\"exp\":" + soon() + ",\"acr\":\"aal2\",\"auth_time\":1786000000}");

        assertThat(attributes).containsEntry("acr", "aal2");
        assertThat(attributes.get("auth_time"))
                .as("the policy does arithmetic on this — a String would silently never elevate")
                .isInstanceOf(Number.class)
                .isEqualTo(1786000000);
        assertThat(attributes).doesNotContainKey("act_chain");
    }

    @Test // an agent token: the delegation claim's ARRAY value survives, and its key is what matters
    void anAgentTokenCarriesTheDelegationClaimKey() {
        Map<String, Object> attributes = attributesOf(
                "{\"sub\":\"anna\",\"exp\":" + soon() + ",\"act_chain\":[\"agent-readonly\"]}");

        assertThat(attributes).containsEntry("act_chain", List.of("agent-readonly"));
    }

    @Test // a FALSY delegation claim still arrives as a KEY — the presence-test's whole premise
    void aFalsyDelegationClaimStillArrivesAsAKey() {
        assertThat(attributesOf("{\"sub\":\"a\",\"exp\":" + soon() + ",\"act_chain\":false}"))
                .containsKey("act_chain");
        assertThat(attributesOf("{\"sub\":\"a\",\"exp\":" + soon() + ",\"act_chain\":[]}"))
                .containsKey("act_chain");
        assertThat(attributesOf("{\"sub\":\"a\",\"exp\":" + soon() + ",\"act_chain\":\"\"}"))
                .containsKey("act_chain");
    }

    @Test // absent claims stay ABSENT — never a null entry a presence-test would misread
    void absentClaimsStayAbsent() {
        Map<String, Object> attributes = attributesOf("{\"sub\":\"anna\",\"exp\":" + soon() + "}");

        assertThat(attributes).doesNotContainKeys("acr", "auth_time", "act_chain");
    }

    @Test // the ordinary ROPC-shaped token: acr present, auth_time structurally absent (ADR 0030 Context)
    void aRopcShapedTokenCarriesAcrButNoAuthTime() {
        Map<String, Object> attributes = attributesOf(
                "{\"sub\":\"editor\",\"exp\":" + soon() + ",\"acr\":\"aal1\"}");

        assertThat(attributes).containsEntry("acr", "aal1").doesNotContainKey("auth_time");
    }
}
