package dev.dmitriikonovalov.example.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Error-contract integration tests (Phase 5.9 T2) — QA cases I1, I2, I3-201 — against the real (secured)
 * chain + real Postgres, asserting the RFC-7807 {@code application/problem+json} body and the typed
 * {@code errorCode} for each status, and the {@code Location} header on a {@code 201}.
 *
 * <p>I2b/I2c (the 422 illegal-tag and 503 dictionary-outage bodies) are asserted in
 * {@link CategoryTagAssignmentIT}, which already drives those paths. I3 (a denied
 * {@code @OpaPreAuthorize} → 403 {@code ACCESS_DENIED} {@code problem+json}) is covered by the library
 * unit test (U5, the inherited {@code AbstractProblemAdvice} mapping) and the live e2e (E1); the permissive
 * test chain here always grants, so a deny cannot be driven in this harness.
 */
@AutoConfigureMockMvc
class ErrorContractIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // I1 — a GET of a missing catalog → 404 problem+json with RESOURCE_NOT_FOUND.
    @Test
    void notFoundIsProblemJsonWithResourceNotFound() throws Exception {
        UUID missing = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/catalogs/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("/problems/resource-not-found"))
                .andExpect(jsonPath("$.title").value(Matchers.not(Matchers.blankOrNullString())))
                .andExpect(jsonPath("$.instance").value("/api/v1/catalogs/" + missing))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").doesNotExist()); // clean replacement — no legacy field
    }

    // I2 — a malformed create body (blank name violates minLength) → 400 problem+json VALIDATION_FAILED.
    @Test
    void validationFailureIsProblemJsonWithValidationFailed() throws Exception {
        mockMvc.perform(post("/api/v1/catalogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    // I3-201 — a successful create carries Location: /api/v1/catalogs/<id> matching the created id.
    @Test
    void createCarriesLocationHeader() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/catalogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Electronics\",\"description\":\"d\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost/api/v1/catalogs/" + id);
    }
}
