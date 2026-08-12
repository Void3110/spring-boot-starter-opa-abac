package dev.dmitriikonovalov.example.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dmitriikonovalov.example.catalog.config.TagDefinitionClient;
import dev.dmitriikonovalov.example.catalog.config.TagDefinitionView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Production-tier T2 (I3, I4) against real Postgres: the operator-managed key is unwritable through every
 * public tag path and writable through the internal operator path — the two halves of "operator-managed
 * end to end", asserted over HTTP rather than at the service seam.
 *
 * <p>A stubbed {@link TagDefinitionClient} supplies the dictionary (`env` operator-managed, `sensitivity`
 * ordinary), so no user-service is needed; T1 already proved the real projection carries the flag.
 */
@AutoConfigureMockMvc
@Import(OperatorManagedTagWriteIT.StubTagClientConfig.class)
class OperatorManagedTagWriteIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String createCatalog() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/catalogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tiered\",\"description\":\"d\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    private void operatorSets(String catalogId, String tagsJson) throws Exception {
        mockMvc.perform(post("/internal/bootstrap/resource-tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"catalog\",\"resourceId\":\"" + catalogId
                                + "\",\"tags\":" + tagsJson + "}"))
                .andExpect(status().isOk());
    }

    // --- I3: no public path may move an operator-managed key -------------------

    @Test
    void assigningTheManagedKeyThroughAPublicUpdateIsAConflict() throws Exception {
        String catalogId = createCatalog();

        mockMvc.perform(put("/api/v1/catalogs/{id}", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tiered\",\"description\":\"d\","
                                + "\"tags\":{\"env\":\"staging\",\"sensitivity\":\"public\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TAG_OPERATOR_MANAGED"));
    }

    @Test
    void theSameUpdateWithoutTheManagedKeySucceeds() throws Exception {
        String catalogId = createCatalog();

        mockMvc.perform(put("/api/v1/catalogs/{id}", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tiered\",\"description\":\"d\","
                                + "\"tags\":{\"sensitivity\":\"public\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.sensitivity").value("public"));
    }

    @Test
    void strippingTheManagedKeyThroughAPublicUpdateIsAConflict() throws Exception {
        String catalogId = createCatalog();
        operatorSets(catalogId, "{\"env\":\"staging\"}");

        // The owner submits a tag map that simply omits `env` — under full-map-replace semantics that IS
        // the strip, and it is the exact move the e2e E5 cell makes.
        mockMvc.perform(put("/api/v1/catalogs/{id}", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tiered\",\"description\":\"d\","
                                + "\"tags\":{\"sensitivity\":\"public\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TAG_OPERATOR_MANAGED"));

        // …and the empty-map shortcut is the same strip by another route.
        mockMvc.perform(put("/api/v1/catalogs/{id}", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tiered\",\"description\":\"d\",\"tags\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TAG_OPERATOR_MANAGED"));
    }

    @Test
    void reValuingTheManagedKeyThroughAPublicUpdateIsAConflict() throws Exception {
        String catalogId = createCatalog();
        operatorSets(catalogId, "{\"env\":\"staging\"}");

        mockMvc.perform(put("/api/v1/catalogs/{id}", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tiered\",\"description\":\"d\","
                                + "\"tags\":{\"env\":\"production\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TAG_OPERATOR_MANAGED"));
    }

    @Test
    void echoingTheManagedKeyLeavesOrdinaryTagEditingUnfrozen() throws Exception {
        String catalogId = createCatalog();
        operatorSets(catalogId, "{\"env\":\"staging\"}");

        mockMvc.perform(put("/api/v1/catalogs/{id}", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tiered\",\"description\":\"d\","
                                + "\"tags\":{\"env\":\"staging\",\"sensitivity\":\"internal\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.env").value("staging"))
                .andExpect(jsonPath("$.tags.sensitivity").value("internal"));
    }

    @Test
    void tagOnCreateCannotSmuggleTheManagedKey() throws Exception {
        String catalogId = createCatalog();

        mockMvc.perform(post("/api/v1/catalogs/{c}/categories", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cat\",\"description\":\"d\","
                                + "\"tags\":{\"env\":\"dev\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TAG_OPERATOR_MANAGED"));
    }

    // --- I4: the operator path, happy and non-happy ----------------------------

    @Test
    void theOperatorPathMergesRatherThanReplaces() throws Exception {
        String catalogId = createCatalog();

        mockMvc.perform(put("/api/v1/catalogs/{id}", catalogId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tiered\",\"description\":\"d\","
                                + "\"tags\":{\"sensitivity\":\"public\"}}"))
                .andExpect(status().isOk());

        // Posting only `env` must leave the public flow's `sensitivity` alone.
        mockMvc.perform(post("/internal/bootstrap/resource-tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"catalog\",\"resourceId\":\"" + catalogId
                                + "\",\"tags\":{\"env\":\"production\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.env").value("production"))
                .andExpect(jsonPath("$.sensitivity").value("public"));

        mockMvc.perform(get("/api/v1/catalogs/{id}", catalogId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.env").value("production"))
                .andExpect(jsonPath("$.tags.sensitivity").value("public"));
    }

    @Test
    void theOperatorPathIsIdempotentAndCanRemoveWithNull() throws Exception {
        String catalogId = createCatalog();

        operatorSets(catalogId, "{\"env\":\"staging\"}");
        operatorSets(catalogId, "{\"env\":\"staging\"}");
        mockMvc.perform(get("/api/v1/catalogs/{id}", catalogId))
                .andExpect(jsonPath("$.tags.env").value("staging"));

        mockMvc.perform(post("/internal/bootstrap/resource-tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"catalog\",\"resourceId\":\"" + catalogId
                                + "\",\"tags\":{\"env\":null}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.env").doesNotExist());

        mockMvc.perform(get("/api/v1/catalogs/{id}", catalogId))
                .andExpect(jsonPath("$.tags.env").doesNotExist());
    }

    @Test
    void anIllegalEnumValueIsUnprocessable() throws Exception {
        String catalogId = createCatalog();

        mockMvc.perform(post("/internal/bootstrap/resource-tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"catalog\",\"resourceId\":\"" + catalogId
                                + "\",\"tags\":{\"env\":\"prod\"}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TAG_VALUE_ILLEGAL"));
    }

    @Test
    void anUnknownKeyIsUnprocessable() throws Exception {
        String catalogId = createCatalog();

        mockMvc.perform(post("/internal/bootstrap/resource-tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"catalog\",\"resourceId\":\"" + catalogId
                                + "\",\"tags\":{\"tier\":\"gold\"}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TAG_VALUE_ILLEGAL"));
    }

    @Test
    void anUnknownResourceIsNotFound() throws Exception {
        mockMvc.perform(post("/internal/bootstrap/resource-tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"catalog\",\"resourceId\":\"" + UUID.randomUUID()
                                + "\",\"tags\":{\"env\":\"dev\"}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void anUnknownResourceTypeIsNotFound() throws Exception {
        mockMvc.perform(post("/internal/bootstrap/resource-tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"widget\",\"resourceId\":\"" + UUID.randomUUID()
                                + "\",\"tags\":{\"env\":\"dev\"}}"))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration
    static class StubTagClientConfig {

        @Bean
        @Primary
        TagDefinitionClient stubTagDefinitionClient() {
            return new TagDefinitionClient(new ObjectMapper(), "http://unused", 100) {
                @Override
                public List<TagDefinitionView> fetchApplicable(String resourceType, String resourceId) {
                    return List.of(
                            new TagDefinitionView(
                                    "env", "ENUM", "SINGLE",
                                    List.of("production", "staging", "dev"), null, true),
                            new TagDefinitionView(
                                    "sensitivity", "ENUM", "SINGLE",
                                    List.of("public", "internal", "confidential"), null, false));
                }
            };
        }
    }
}
