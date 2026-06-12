package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.VersionConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit test for the {@link AbstractProblemAdvice} {@link VersionConflictException} mapping (QA case
 * U13): {@code 409 application/problem+json}, {@code errorCode=STATE_CONFLICT}, a static detail free
 * of stack traces, versions, or other internals.
 */
class VersionConflictAdviceTest {

    private final AbstractProblemAdvice advice = new AbstractProblemAdvice() {};

    @Test // U13
    void versionConflictMapsTo409StateConflict() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/catalogs/1/categories/2");
        VersionConflictException ex = new VersionConflictException("category/c-2", 3, 4);

        ResponseEntity<ProblemDetail> response = advice.handleVersionConflict(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.errorCode()).isEqualTo("STATE_CONFLICT");
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.instance()).isEqualTo("/catalogs/1/categories/2");
        // the body carries no internals: no versions, no exception text
        assertThat(body.detail()).doesNotContain("3").doesNotContain("4").doesNotContain("category/c-2");
    }
}
