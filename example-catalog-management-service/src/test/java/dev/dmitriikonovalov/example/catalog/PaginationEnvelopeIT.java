package dev.dmitriikonovalov.example.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The 5.95 list-envelope contract over the catalog service's three public lists (QA cases I5–I6):
 * envelope members + defaults, the fixed {@code createdAt ASC, id ASC} order, the strict param
 * negatives ({@code 400 VALIDATION_FAILED} {@code problem+json}, no clamping), and past-the-end
 * ({@code 200} + empty {@code items} + the exact {@code count}). Runs through the real (secured) chain
 * with the permissive test subject; deterministic counts are asserted on catalog-scoped lists (the
 * shared container may hold rows from sibling ITs, so the top-level catalogs list asserts shape only).
 */
@AutoConfigureMockMvc
class PaginationEnvelopeIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String catalogId;
    private String firstCategoryId;
    private String secondCategoryId;
    private String thirdCategoryId;

    @BeforeEach
    void seedHierarchy() throws Exception {
        catalogId = create("/api/v1/catalogs", "{\"name\":\"Paging\",\"description\":\"d\"}");
        String base = "/api/v1/catalogs/" + catalogId + "/categories";
        firstCategoryId = create(base, "{\"name\":\"First\"}");
        secondCategoryId = create(base, "{\"name\":\"Second\"}");
        thirdCategoryId = create(base, "{\"name\":\"Third\"}");
    }

    // I5 — no params → the defaults (page=0, perPage=20); all envelope members present; count is the
    // authorized total; rows in creation (createdAt, id) order.
    @Test
    void envelopeAndDefaults_onCategoriesList() throws Exception {
        mockMvc.perform(get("/api/v1/catalogs/{c}/categories", catalogId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.perPage").value(20))
                .andExpect(jsonPath("$.items", Matchers.hasSize(3)))
                .andExpect(jsonPath("$.items[0].id").value(firstCategoryId))
                .andExpect(jsonPath("$.items[1].id").value(secondCategoryId))
                .andExpect(jsonPath("$.items[2].id").value(thirdCategoryId));
    }

    // I5 — an explicit window: perPage=2 → first page [First, Second] with the exact count; page=1 →
    // [Third]; page/perPage echo the request verbatim.
    @Test
    void explicitWindow_slicesInFixedOrder_withExactCount() throws Exception {
        mockMvc.perform(get("/api/v1/catalogs/{c}/categories", catalogId)
                        .param("page", "0").param("perPage", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.perPage").value(2))
                .andExpect(jsonPath("$.items", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(firstCategoryId))
                .andExpect(jsonPath("$.items[1].id").value(secondCategoryId));

        mockMvc.perform(get("/api/v1/catalogs/{c}/categories", catalogId)
                        .param("page", "1").param("perPage", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.perPage").value(2))
                .andExpect(jsonPath("$.items", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(thirdCategoryId));
    }

    // I5 — the catalogs and products lists carry the same envelope (shape + echo; the catalogs count is
    // not pinned — the shared container holds sibling ITs' rows).
    @Test
    void envelope_onCatalogsAndProductsLists() throws Exception {
        mockMvc.perform(get("/api/v1/catalogs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.perPage").value(20))
                .andExpect(jsonPath("$.items").isArray());

        String productId = create(
                "/api/v1/catalogs/" + catalogId + "/categories/" + firstCategoryId + "/products",
                "{\"name\":\"P\",\"priceCents\":100,\"currency\":\"USD\"}");
        mockMvc.perform(get("/api/v1/catalogs/{c}/categories/{cat}/products", catalogId, firstCategoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.perPage").value(20))
                .andExpect(jsonPath("$.items[0].id").value(productId));
    }

    // I6 — the strict negatives: out-of-bounds params → 400 VALIDATION_FAILED problem+json, no clamping.
    @Test
    void boundsViolations_are400ValidationFailed() throws Exception {
        for (String[] bad : new String[][] {{"perPage", "101"}, {"perPage", "0"}, {"page", "-1"}}) {
            mockMvc.perform(get("/api/v1/catalogs/{c}/categories", catalogId)
                            .param(bad[0], bad[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
        // The same contract on a coarse-gated list (the bounds live in the shared spec params).
        mockMvc.perform(get("/api/v1/catalogs").param("perPage", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    // I6 — past-the-end: 200 + empty items + the exact count (never a 404 — the last page is
    // subject-relative under ABAC).
    @Test
    void pastTheEnd_is200EmptyWithExactCount() throws Exception {
        mockMvc.perform(get("/api/v1/catalogs/{c}/categories", catalogId)
                        .param("page", "7").param("perPage", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.page").value(7))
                .andExpect(jsonPath("$.perPage").value(2))
                .andExpect(jsonPath("$.items", Matchers.hasSize(0)));
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
