package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link JwtClaimsSubjectExtractor} — QA cases U11–U18, U21. */
class JwtClaimsSubjectExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JwtClaimsSubjectExtractor extractor =
            new JwtClaimsSubjectExtractor(MAPPER, SubjectClaimsConfig.defaults());

    // --- helpers -------------------------------------------------------------

    private static String b64url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Build a 3-segment token "header.payload.signature" with the given payload JSON and dummy sig. */
    private static String jwt(String payloadJson) {
        return b64url("{\"alg\":\"RS256\"}") + "." + b64url(payloadJson) + ".dummy-signature-not-verified";
    }

    private static HttpServletRequest requestWithAuth(String headerValue) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn(headerValue);
        return req;
    }

    private Optional<AbacContext.Subject> extract(String payloadJson) {
        return extractor.extract(requestWithAuth("Bearer " + jwt(payloadJson)));
    }

    private static long future() {
        return Instant.now().plusSeconds(3600).getEpochSecond();
    }

    private static long past() {
        return Instant.now().minusSeconds(3600).getEpochSecond();
    }

    // --- cases ---------------------------------------------------------------

    @Test // U11 — well-formed Keycloak-shaped token
    void wellFormedToken_mapsIdRolesUsername() {
        String payload = "{\"sub\":\"user-1\",\"preferred_username\":\"alice\","
                + "\"realm_access\":{\"roles\":[\"catalog-viewer\",\"catalog-editor\"]},\"exp\":" + future() + "}";
        Optional<AbacContext.Subject> subject = extract(payload);
        assertThat(subject).isPresent();
        assertThat(subject.get().id()).isEqualTo("user-1");
        assertThat(subject.get().roles()).containsExactly("catalog-viewer", "catalog-editor");
        assertThat(subject.get().attributes()).containsEntry("username", "alice");
    }

    @Test // U12 — missing sub
    void missingSub_empty() {
        assertThat(extract("{\"preferred_username\":\"alice\",\"exp\":" + future() + "}")).isEmpty();
    }

    @Test // U13 — missing roles → empty roles, not a failure
    void missingRoles_emptyRoles() {
        Optional<AbacContext.Subject> subject = extract("{\"sub\":\"user-1\",\"exp\":" + future() + "}");
        assertThat(subject).isPresent();
        assertThat(subject.get().roles()).isEmpty();
    }

    @Test // U14 — flat configured roles claim
    void flatRolesClaim_configured() {
        JwtClaimsSubjectExtractor custom = new JwtClaimsSubjectExtractor(
                MAPPER, new SubjectClaimsConfig("sub", "roles", "preferred_username", List.of(), true));
        String payload = "{\"sub\":\"user-1\",\"roles\":[\"admin\"],\"exp\":" + future() + "}";
        Optional<AbacContext.Subject> subject = custom.extract(requestWithAuth("Bearer " + jwt(payload)));
        assertThat(subject).isPresent();
        assertThat(subject.get().roles()).containsExactly("admin");
    }

    @Test // U15 — expired exp
    void expiredToken_empty() {
        assertThat(extract("{\"sub\":\"user-1\",\"exp\":" + past() + "}")).isEmpty();
    }

    @Test // U15b — expiry validation off → expired token still extracts
    void expiredToken_acceptedWhenValidationOff() {
        JwtClaimsSubjectExtractor noExpiry = new JwtClaimsSubjectExtractor(
                MAPPER, new SubjectClaimsConfig("sub", "realm_access.roles", "preferred_username", List.of(), false));
        Optional<AbacContext.Subject> subject =
                noExpiry.extract(requestWithAuth("Bearer " + jwt("{\"sub\":\"user-1\",\"exp\":" + past() + "}")));
        assertThat(subject).isPresent();
    }

    @Test // U16a — 2-segment token
    void twoSegmentToken_empty() {
        String twoSeg = "header.payload";
        assertThat(extractor.extract(requestWithAuth("Bearer " + twoSeg))).isEmpty();
    }

    @Test // U16b — non-JSON payload
    void nonJsonPayload_empty() {
        String token = "h." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-json".getBytes(StandardCharsets.UTF_8)) + ".sig";
        assertThat(extractor.extract(requestWithAuth("Bearer " + token))).isEmpty();
    }

    @Test // U17 — no Authorization header
    void noAuthHeader_empty() {
        assertThat(extractor.extract(requestWithAuth(null))).isEmpty();
        assertThat(extractor.extract(requestWithAuth("Basic abc"))).isEmpty();
    }

    @Test // U18 — configurable claim paths honored
    void configurableClaimPaths() {
        JwtClaimsSubjectExtractor custom = new JwtClaimsSubjectExtractor(
                MAPPER, new SubjectClaimsConfig("user_id", "authz.groups", "login", List.of("tier"), true));
        String payload = "{\"user_id\":\"u-9\",\"login\":\"bob\",\"tier\":\"gold\","
                + "\"authz\":{\"groups\":[\"g1\"]},\"exp\":" + future() + "}";
        Optional<AbacContext.Subject> subject = custom.extract(requestWithAuth("Bearer " + jwt(payload)));
        assertThat(subject).isPresent();
        assertThat(subject.get().id()).isEqualTo("u-9");
        assertThat(subject.get().roles()).containsExactly("g1");
        assertThat(subject.get().attributes()).containsEntry("username", "bob").containsEntry("tier", "gold");
    }

    @Test // U21 — no signature verification (garbage sig still extracts)
    void garbageSignature_stillExtracts() {
        String token = b64url("{\"alg\":\"RS256\"}") + "." + b64url("{\"sub\":\"user-1\",\"exp\":" + future() + "}")
                + ".tHiS-iS-nOt-a-vAlId-sIgNaTuRe";
        Optional<AbacContext.Subject> subject = extractor.extract(requestWithAuth("Bearer " + token));
        assertThat(subject).isPresent();
        assertThat(subject.get().id()).isEqualTo("user-1");
    }
}
