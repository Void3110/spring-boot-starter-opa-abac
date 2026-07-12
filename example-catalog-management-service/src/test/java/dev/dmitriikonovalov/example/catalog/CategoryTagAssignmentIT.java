package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionClient;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionFetchException;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionView;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Ticket-3 assignment ITs (A1–A4, A6) against real Postgres. A {@link TagDefinitionClient} stub supplies
 * a controllable applicable-set (sensitivity ENUM/SINGLE, region ENUM/MULTI) — no user-service needed — so
 * the validation + storage path is exercised end to end: a legal assignment persists into {@code tags}
 * (scalar + array); an unknown key / enum miss / cardinality mismatch → 422; a definitions-fetch failure
 * → 503 (fail-closed, nothing stored).
 *
 * <p>A7 (a viewer cannot assign) is the existing {@code assign-tags} second-decision authorization (Phase 6.5),
 * proven in the e2e matrix (ticket 6); here the permissive editor always has write.
 */
@AutoConfigureMockMvc
@Import(CategoryTagAssignmentIT.StubTagClientConfig.class)
class CategoryTagAssignmentIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void resetStub() {
        StubTagClientConfig.failNextFetch = false;
    }

    private String createCatalog() throws Exception {
        return create("/api/v1/catalogs", "{\"name\":\"Electronics\",\"description\":\"d\"}");
    }

    // --- A1: legal tags persist (scalar + array) ------------------------------

    @Test
    void assignsLegalTags() throws Exception {
        String catalogId = createCatalog();
        MvcResult result = mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Computers\",\"tags\":{"
                                + "\"sensitivity\":\"internal\",\"region\":[\"emea\",\"amer\"]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tags.sensitivity").value("internal"))
                .andExpect(jsonPath("$.tags.region", org.hamcrest.Matchers.containsInAnyOrder("emea", "amer")))
                .andReturn();
        // Re-read to prove it persisted.
        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{cat}", catalogId, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.sensitivity").value("internal"));
    }

    // --- A2: unknown key → 422 ------------------------------------------------

    @Test
    void unknownKeyIsRejected() throws Exception {
        String catalogId = createCatalog();
        // I2b: 422 carries application/problem+json with errorCode TAG_VALUE_ILLEGAL.
        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"C\",\"tags\":{\"nope\":\"x\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("TAG_VALUE_ILLEGAL"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    // --- A3: enum miss → 422 --------------------------------------------------

    @Test
    void enumMissIsRejected() throws Exception {
        String catalogId = createCatalog();
        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"C\",\"tags\":{\"sensitivity\":\"secret\"}}"))
                .andExpect(status().isUnprocessableContent());
    }

    // --- A4: cardinality mismatch (SINGLE given array) → 422 ------------------

    @Test
    void cardinalityMismatchIsRejected() throws Exception {
        String catalogId = createCatalog();
        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"C\",\"tags\":{\"sensitivity\":[\"public\",\"internal\"]}}"))
                .andExpect(status().isUnprocessableContent());
    }

    // --- A6: definitions-fetch failure → 503, nothing stored ------------------

    @Test
    void definitionsFetchFailureRejectsTheWrite() throws Exception {
        String catalogId = createCatalog();
        StubTagClientConfig.failNextFetch = true;
        // I2c: the fail-closed 503 carries problem+json with DEPENDENCY_UNAVAILABLE; nothing is stored.
        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"C\",\"tags\":{\"sensitivity\":\"internal\"}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503));

        // No category leaked through with the tag (the envelope reports an authorized total of 0).
        var list = mockMvc.perform(get("/api/v1/catalogs/{c}/categories", catalogId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(list.getResponse().getContentAsString());
        assertThat(body.get("count").asLong()).isZero();
        assertThat(body.get("items")).isEmpty();
    }

    // --- a category with no tags still works (no fetch) ------------------------

    @Test
    void noTagsNeedsNoFetch() throws Exception {
        String catalogId = createCatalog();
        StubTagClientConfig.failNextFetch = true; // even if a fetch WOULD fail, none happens
        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Untagged\"}"))
                .andExpect(status().isCreated());
    }

    private String create(String url, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    @TestConfiguration
    static class StubTagClientConfig {

        static volatile boolean failNextFetch = false;

        @Bean
        @Primary
        TagDefinitionClient stubTagDefinitionClient() {
            return new TagDefinitionClient(new ObjectMapper(), "http://unused", 100) {
                @Override
                public List<TagDefinitionView> fetchApplicable(String resourceType, String resourceId) {
                    if (failNextFetch) {
                        throw new TagDefinitionFetchException("stub: simulated fetch failure");
                    }
                    return List.of(
                            new TagDefinitionView(
                                    "sensitivity", "ENUM", "SINGLE",
                                    List.of("public", "internal", "confidential"), null),
                            new TagDefinitionView(
                                    "region", "ENUM", "MULTI", List.of("emea", "amer", "apac"), null));
                }
            };
        }
    }
}
