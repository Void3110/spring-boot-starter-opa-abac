package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;

/**
 * Unit tests for the library error-contract vocabulary and carrier — QA cases U1–U6.
 *
 * <p>Mapping + serialization only — no Spring context, no DB. Asserts the RFC-7807 member names, the
 * {@code (status, code, type, title)} mapping per {@link LibraryErrorCode}, the
 * {@link AccessDeniedException} → {@code ACCESS_DENIED} resolution, and that a foreign app code plugs into
 * the helper unchanged.
 */
class ProblemDetailContractTest {

    private final ProblemDetailFactory factory = new ProblemDetailFactory();

    // A JsonMapper mirroring Spring Boot's defaults for the members we assert (java.time as ISO-8601).
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    // U1 — every LibraryErrorCode exposes code()/problemType()/title().
    @Test
    void everyLibraryCodeExposesTheThreeAccessors() {
        for (LibraryErrorCode code : LibraryErrorCode.values()) {
            assertThat(code.code()).isEqualTo(code.name());
            assertThat(code.problemType())
                    .isEqualTo("/problems/" + code.name().toLowerCase().replace('_', '-'))
                    .startsWith("/problems/")
                    .doesNotContain("_") // kebab, not snake
                    .doesNotContain("://"); // relative, no host
            assertThat(code.title()).isNotBlank();
        }
    }

    // U2 — each LibraryErrorCode carries the expected HTTP status.
    @Test
    void eachLibraryCodeCarriesTheRightStatus() {
        assertThat(LibraryErrorCode.ACCESS_DENIED.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(LibraryErrorCode.DEPENDENCY_UNAVAILABLE.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(LibraryErrorCode.VALIDATION_FAILED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(LibraryErrorCode.RESOURCE_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(LibraryErrorCode.STATE_CONFLICT.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(LibraryErrorCode.TAG_VALUE_ILLEGAL.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(LibraryErrorCode.ROLE_SUBSET_VIOLATION.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // U3 — the helper builds a ProblemDetail from (status, code, detail, instance).
    @Test
    void helperBuildsProblemDetailFromTheTuple() {
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        ProblemDetail body = factory.body(
                HttpStatus.UNPROCESSABLE_ENTITY,
                LibraryErrorCode.TAG_VALUE_ILLEGAL,
                "Unknown tag key: reglon",
                "/api/v1/catalogs/7b/categories");

        assertThat(body.type()).isEqualTo("/problems/tag-value-illegal");
        assertThat(body.title()).isEqualTo("Tag value not permitted by the dictionary");
        assertThat(body.status()).isEqualTo(422);
        assertThat(body.detail()).isEqualTo("Unknown tag key: reglon");
        assertThat(body.instance()).isEqualTo("/api/v1/catalogs/7b/categories");
        assertThat(body.errorCode()).isEqualTo("TAG_VALUE_ILLEGAL");
        assertThat(body.timestamp()).isAfterOrEqualTo(before);
    }

    // U4 — the carrier serializes to exactly the canonical RFC-7807 members; NO `message`; content type.
    @Test
    void carrierSerializesToCanonicalMembersWithNoMessage() throws Exception {
        ResponseEntity<ProblemDetail> response = factory.response(
                HttpStatus.NOT_FOUND,
                LibraryErrorCode.RESOURCE_NOT_FOUND,
                "No such catalog",
                "/api/v1/catalogs/missing");

        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        String json = objectMapper.writeValueAsString(response.getBody());
        assertThat(objectMapper.readTree(json).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "type", "title", "status", "detail", "instance", "errorCode", "timestamp");
        assertThat(json).doesNotContain("\"message\"");
    }

    // U5 — Spring Security AccessDeniedException / AuthorizationDeniedException → ACCESS_DENIED 403.
    @Test
    void accessDeniedResolvesToTheDenyCodeAtForbidden() {
        TestAdvice advice = new TestAdvice();

        ResponseEntity<ProblemDetail> fromAccessDenied =
                advice.handleAccessDenied(new AccessDeniedException("denied"), null);
        assertThat(fromAccessDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fromAccessDenied.getBody().errorCode()).isEqualTo("ACCESS_DENIED");
        assertThat(fromAccessDenied.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        ResponseEntity<ProblemDetail> fromAuthorizationDenied = advice.handleAccessDenied(
                new AuthorizationDeniedException("denied", new AuthorizationDecisionStub()), null);
        assertThat(fromAuthorizationDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fromAuthorizationDenied.getBody().errorCode()).isEqualTo("ACCESS_DENIED");
    }

    // U6 — a foreign app enum implementing ApiErrorCode plugs into the helper unchanged.
    @Test
    void foreignAppCodePlugsIntoTheHelper() {
        ApiErrorCode appCode = AppErrorCode.WIDGET_JAMMED;

        ProblemDetail body = factory.body(
                HttpStatus.CONFLICT, appCode, "The widget is jammed", "/api/v1/widgets/1");

        assertThat(body.errorCode()).isEqualTo("WIDGET_JAMMED");
        assertThat(body.type()).isEqualTo("/problems/widget-jammed");
        assertThat(body.title()).isEqualTo("Widget Jammed"); // default-derived from code()
        assertThat(body.status()).isEqualTo(409);
    }

    /** A stand-in app advice to exercise the inherited AccessDeniedException handler. */
    private static final class TestAdvice extends AbstractProblemAdvice {}

    /** A stand-in application error code — the interface is the only seam. */
    private enum AppErrorCode implements ApiErrorCode {
        WIDGET_JAMMED;

        @Override
        public String code() {
            return name();
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.CONFLICT;
        }
    }

    /**
     * A minimal {@link org.springframework.security.authorization.AuthorizationResult} stub so we can
     * construct an {@link AuthorizationDeniedException} without the full security machinery.
     */
    private static final class AuthorizationDecisionStub
            implements org.springframework.security.authorization.AuthorizationResult {
        @Override
        public boolean isGranted() {
            return false;
        }
    }
}
