package dev.dmitriikonovalov.example.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.example.catalog.support.PermissiveSecurityTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice B4 T6 ITs (I10/I11) for the internal ownership-read contract
 * {@code GET /internal/catalog/{id}/created-by} against <b>real Postgres</b>. A catalog created through
 * the API records {@code created_by} = the (permissive test) subject's sub via the {@code AuditorAware};
 * the endpoint reads it back. A missing catalog → {@code 404} (the resolver maps that to not-owner).
 */
@AutoConfigureMockMvc
class InternalOwnershipIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test // I10 — 200 {createdBy} == the creator sub (the permissive test principal)
    void createdByReturnsTheCreatorSub() throws Exception {
        String catalogId = create("/api/v1/catalogs", "{\"name\":\"Owned\",\"description\":\"d\"}");

        mockMvc.perform(get("/internal/catalog/{id}/created-by", catalogId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy")
                        .value(PermissiveSecurityTestConfig.TEST_PRINCIPAL.toString()));
    }

    @Test // I11 — a missing catalog → 404 (resolver → not-owner)
    void missingCatalogIs404() throws Exception {
        mockMvc.perform(get("/internal/catalog/{id}/created-by", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private String create(String path, String body) throws Exception {
        var result = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
