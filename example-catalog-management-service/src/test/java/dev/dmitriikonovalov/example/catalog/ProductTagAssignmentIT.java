package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionClient;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionFetchException;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionView;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The {@link CategoryTagAssignmentIT} cells applied to the PRODUCT create (taggable products): the
 * same stubbed dictionary, the same validation + storage path, one level deeper — a legal assignment
 * persists into the product's {@code tags} (scalar + array); an unknown key / enum miss / cardinality
 * mismatch → 422; a definitions-fetch failure → 503 (fail-closed, nothing stored). The dictionary is
 * addressed by the governing ROOT (the catalog), exactly as the category path.
 *
 * <p>WHO may assign is the dispatch/authorization question, pinned in {@code TagDecisionGateIT}; here
 * the permissive chain always allows.
 */
@AutoConfigureMockMvc
@Import(ProductTagAssignmentIT.StubTagClientConfig.class)
class ProductTagAssignmentIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void resetStub() {
        StubTagClientConfig.failNextFetch = false;
    }

    /** A catalog + category to hang products off; returns the products collection URL. */
    private String productsUrl() throws Exception {
        String catalogId = create("/api/v1/catalogs", "{\"name\":\"Electronics\",\"description\":\"d\"}");
        String categoryId = create("/api/v1/catalogs/" + catalogId + "/categories",
                "{\"name\":\"Computers\"}");
        return "/api/v1/catalogs/" + catalogId + "/categories/" + categoryId + "/products";
    }

    // --- legal tags persist on create (scalar + array), and survive a re-read -----

    @Test
    void assignsLegalTagsOnCreate() throws Exception {
        String url = productsUrl();
        MvcResult result = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Laptop\",\"priceCents\":199900,\"currency\":\"USD\",\"tags\":{"
                                + "\"sensitivity\":\"internal\",\"region\":[\"emea\",\"amer\"]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tags.sensitivity").value("internal"))
                .andExpect(jsonPath("$.tags.region", org.hamcrest.Matchers.containsInAnyOrder("emea", "amer")))
                .andReturn();
        // Re-read to prove it persisted.
        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(get(url + "/{p}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.sensitivity").value("internal"));
    }

    // --- unknown key / enum miss / cardinality mismatch → 422 ---------------------

    @Test
    void unknownKeyIsRejected() throws Exception {
        mockMvc.perform(post(productsUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"priceCents\":100,\"currency\":\"USD\","
                                + "\"tags\":{\"nope\":\"x\"}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("TAG_VALUE_ILLEGAL"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void enumMissIsRejected() throws Exception {
        mockMvc.perform(post(productsUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"priceCents\":100,\"currency\":\"USD\","
                                + "\"tags\":{\"sensitivity\":\"secret\"}}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cardinalityMismatchIsRejected() throws Exception {
        mockMvc.perform(post(productsUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"priceCents\":100,\"currency\":\"USD\","
                                + "\"tags\":{\"sensitivity\":[\"public\",\"internal\"]}}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // --- definitions-fetch failure → 503, nothing stored --------------------------

    @Test
    void definitionsFetchFailureRejectsTheWrite() throws Exception {
        String url = productsUrl();
        StubTagClientConfig.failNextFetch = true;
        mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"priceCents\":100,\"currency\":\"USD\","
                                + "\"tags\":{\"sensitivity\":\"internal\"}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503));

        // No product leaked through with the tag (the envelope reports an authorized total of 0).
        var list = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(list.getResponse().getContentAsString());
        assertThat(body.get("count").asLong()).isZero();
        assertThat(body.get("items")).isEmpty();
    }

    // --- a product with no tags still works (no fetch) -----------------------------

    @Test
    void noTagsNeedsNoFetch() throws Exception {
        String url = productsUrl();
        StubTagClientConfig.failNextFetch = true; // even if a fetch WOULD fail, none happens
        mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Untagged\",\"priceCents\":100,\"currency\":\"USD\"}"))
                .andExpect(status().isCreated());
    }

    private String create(String url, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
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
