package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dmitriikonovalov.example.catalog.config.SupervisedScopeClient;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.example.catalog.support.PermissiveSecurityTestConfig;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.data.filter.GovernedScopeResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ENGINE-ERRORS I6 — the catalog list over a role-source outage, through the real secured chain and the
 * service's own problem advice, on real Postgres.
 *
 * <p>The governed scope answers (the subject is a member of a catalog), but the role source is down. In
 * 1.3.0 {@code CatalogListAuthorizer} caught the outage into an empty 200 page — "you may see nothing" —
 * and, for a subject who also supervised catalogs, into a silently partial supervised-only page. Since
 * ADR 0037 the outage propagates and the list answers <b>503 {@code DEPENDENCY_UNAVAILABLE}</b>: "not now".
 * The contrast cell flips only the outage off and shows the same request listing the member's catalog.
 * The supervisor cell is the QA row's subject: a member of one catalog who supervises another — the case
 * 1.3.0 answered with the supervised-only page.
 */
@AutoConfigureMockMvc
@Import(CatalogListOutageIT.OutageListConfig.class)
@TestPropertySource(properties = "catalog.role-source=none")
class CatalogListOutageIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;

    private UUID memberCatalog;

    @BeforeEach
    void seed() {
        OutageListConfig.outage = false;
        memberCatalog = catalogs.save(new CatalogEntity(UUID.randomUUID(), "Outage Co", "outage IT")).getId();
        OutageListConfig.governed = List.of(memberCatalog);
        OutageListConfig.supervised = List.of();
    }

    @Test // I6 — the role source is down: refused as "could not decide", never an empty or partial 200
    void roleSourceOutage_answers503_notAnEmptyPage() throws Exception {
        OutageListConfig.outage = true;

        mockMvc.perform(get("/api/v1/catalogs"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("Authorization is temporarily unavailable"))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test // I6 — the QA row's subject: a member who also supervises another catalog. The outage is not
    // narrowed to the supervised-only page; the whole list is refused as "could not decide"
    void roleSourceOutage_memberAndSupervisor_answers503_notTheSupervisedOnlyPage() throws Exception {
        UUID supervisedCatalog =
                catalogs.save(new CatalogEntity(UUID.randomUUID(), "Supervised Co", "outage IT")).getId();
        OutageListConfig.supervised = List.of(supervisedCatalog);
        OutageListConfig.outage = true;

        String body = mockMvc.perform(get("/api/v1/catalogs"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(supervisedCatalog.toString(), memberCatalog.toString());
    }

    @Test // the contrast: the same request with the role source up lists the member's catalog
    void roleSourceUp_listsTheMembersCatalog() throws Exception {
        String body = mockMvc.perform(get("/api/v1/catalogs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(memberCatalog.toString());
    }

    @TestConfiguration
    static class OutageListConfig {
        static volatile boolean outage;
        static volatile List<UUID> governed = List.of();
        static volatile List<UUID> supervised = List.of();

        @Bean
        GovernedScopeResolver outageGovernedScopeResolver() {
            // The permissive test principal governs the seeded catalog — the scope read succeeds.
            return (subject, resourceType) ->
                    PermissiveSecurityTestConfig.TEST_PRINCIPAL.toString().equals(subject) ? governed : List.of();
        }

        /** The real client's type, answering the supervised ids from the test instead of the user-service. */
        @Bean
        SupervisedScopeClient outageSupervisedScopeClient() {
            return new SupervisedScopeClient(new tools.jackson.databind.ObjectMapper(), "http://127.0.0.1:1", 500) {
                @Override
                public List<UUID> supervisedIds(String subject, String resourceType) {
                    return PermissiveSecurityTestConfig.TEST_PRINCIPAL.toString().equals(subject)
                            ? supervised : List.of();
                }
            };
        }

        @Bean
        RoleDefinitionSupplier outageRoleSupplier() {
            return (userId, type, id) -> {
                if (outage) {
                    throw new RoleResolutionException("user-management unavailable");
                }
                return Optional.of(new RoleDefinition("member", Map.of(), Map.of("catalog", List.of("READ"))));
            };
        }
    }
}
