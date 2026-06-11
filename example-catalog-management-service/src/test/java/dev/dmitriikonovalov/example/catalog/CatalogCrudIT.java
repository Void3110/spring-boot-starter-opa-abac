package dev.dmitriikonovalov.example.catalog;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end CRUD walk-through over the Catalog → Category → Product hierarchy,
 * driven through the real Spring MVC stack against a real Postgres (Testcontainers)
 * with the Liquibase migrations applied. Proves controller wiring, DTO mapping,
 * validation, and the actual deployed schema.
 */
@AutoConfigureMockMvc
class CatalogCrudIT extends AbstractPostgresIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void fullHierarchyCrud() throws Exception {
        // --- Catalog ---
        String catalogId = create("/api/v1/catalogs", """
                {"name":"Electronics","description":"All electronics"}""");

        mockMvc.perform(get("/api/v1/catalogs/{id}", catalogId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Electronics"));

        // --- Root category ---
        String rootCategoryId = create(
                "/api/v1/catalogs/" + catalogId + "/categories", """
                {"name":"Computers"}""");

        // --- Child category (hierarchy) ---
        String childCategoryId = create(
                "/api/v1/catalogs/" + catalogId + "/categories",
                "{\"name\":\"Laptops\",\"parentId\":\"" + rootCategoryId + "\"}");

        // Children-only filter returns just the laptop sub-category (in the 5.95 list envelope).
        mockMvc.perform(get("/api/v1/catalogs/{c}/categories", catalogId)
                        .param("parentId", rootCategoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(childCategoryId))
                .andExpect(jsonPath("$.items[0].parentId").value(rootCategoryId));

        // --- Product under the child category ---
        String productId = create(
                "/api/v1/catalogs/" + catalogId + "/categories/" + childCategoryId + "/products", """
                {"name":"UltraBook 14","sku":"UB-14","priceCents":129900,"currency":"USD"}""");

        mockMvc.perform(get(
                        "/api/v1/catalogs/{c}/categories/{cat}/products/{p}",
                        catalogId, childCategoryId, productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("UltraBook 14"))
                .andExpect(jsonPath("$.priceCents").value(129900))
                .andExpect(jsonPath("$.categoryId").value(childCategoryId));

        // --- Delete product ---
        mockMvc.perform(delete(
                        "/api/v1/catalogs/{c}/categories/{cat}/products/{p}",
                        catalogId, childCategoryId, productId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(
                        "/api/v1/catalogs/{c}/categories/{cat}/products/{p}",
                        catalogId, childCategoryId, productId))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/v1/catalogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCatalogReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/catalogs/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    /** POST the given JSON body and return the created resource's id. */
    private String create(String url, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }
}
