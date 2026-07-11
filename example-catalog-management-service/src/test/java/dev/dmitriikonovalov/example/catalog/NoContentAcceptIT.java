package dev.dmitriikonovalov.example.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The {@code produces} content-negotiation contract on the catalog service's three 204-only DELETE
 * ops ({@code deleteCatalog}, {@code deleteCategory}, {@code deleteProduct}): a bare
 * {@code Accept: application/json} answers <b>204 with an empty body — not 406</b> — and no
 * {@code Accept} at all still answers 204. The sibling of the user-mgmt fix (DIRECTORY-QUERY-FILTERS
 * T3), found by its deep-review's sibling sweep: a 204-only op whose declared content is only its
 * errors' {@code problem+json} generates {@code produces={problem+json}}, and negotiation 406s the
 * JSON {@code Accept} before the handler runs. Fresh fixtures per header variant (destructive ops).
 */
@AutoConfigureMockMvc
class NoContentAcceptIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String create(String path, String body) throws Exception {
        var result = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /** Deletes product → category → catalog, each with the given Accept (null = no Accept header). */
    private void assertAllThreeDeletesAnswer204(MediaType accept) throws Exception {
        String catalogId = create("/api/v1/catalogs", """
                {"name":"Accept fixture","description":null}""");
        String categoryId = create("/api/v1/catalogs/" + catalogId + "/categories", """
                {"name":"Accept category"}""");
        String productId = create(
                "/api/v1/catalogs/" + catalogId + "/categories/" + categoryId + "/products", """
                {"name":"Accept product","sku":"ACC-1","priceCents":100,"currency":"USD"}""");

        deleteExpecting204(
                "/api/v1/catalogs/" + catalogId + "/categories/" + categoryId + "/products/" + productId,
                accept);
        deleteExpecting204("/api/v1/catalogs/" + catalogId + "/categories/" + categoryId, accept);
        deleteExpecting204("/api/v1/catalogs/" + catalogId, accept);
    }

    private void deleteExpecting204(String path, MediaType accept) throws Exception {
        MockHttpServletRequestBuilder request = delete(path);
        if (accept != null) {
            request = request.accept(accept);
        }
        mockMvc.perform(request)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    // The headline: a bare Accept: application/json is admitted (204), no longer 406'd.
    @Test
    void bareJsonAcceptAnswers204NotOn406OnAllThreeDeletes() throws Exception {
        assertAllThreeDeletesAnswer204(MediaType.APPLICATION_JSON);
    }

    // The regression guard: no Accept header at all still answers 204 (as it always did).
    @Test
    void absentAcceptStillAnswers204OnAllThreeDeletes() throws Exception {
        assertAllThreeDeletesAnswer204(null);
    }
}
