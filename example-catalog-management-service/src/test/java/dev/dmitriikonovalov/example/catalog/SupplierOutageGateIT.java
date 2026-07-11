package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dmitriikonovalov.example.catalog.config.CatalogHierarchyService;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The headline IT for Slice B2 — the C1/C4 cut, real Postgres (QA I1/I2).
 *
 * <p>A subject carrying realm {@code catalog-editor} performs an {@code @OpaPreAuthorize}-gated catalog
 * write. With a {@link RoleDefinitionSupplier} test bean that <strong>throws</strong>
 * {@link RoleResolutionException} (a simulated user-management outage), the gate must answer
 * <strong>{@code 403 ACCESS_DENIED}</strong> and <strong>never call OPA</strong> — so the policy's realm
 * fallback is never fed the outage and can never widen the grant; the handler never runs (the row is
 * byte-identical). The <strong>contrast</strong> cell proves the designed path is unbroken: the same
 * subject with a supplier that returns {@code Optional.empty()} (an authoritative no-role) still reaches
 * OPA, so the realm fallback decides exactly as before.
 *
 * <p>The {@link OpaClient} is stubbed (this is a gate-boundary IT, not an OPA-policy IT): the contrast
 * cell asserts the empty path <em>reaches</em> OPA (which here grants), while the outage path never does.
 * The real Rego realm-fallback grant is covered end-to-end by the newman suite + {@code opa test}
 * (157/157, unchanged by B2).
 */
@SpringBootTest(properties = {"catalog.role-source=none", "opa.abac.resource-resolution.enabled=false"})
@Testcontainers
@AutoConfigureMockMvc
@Import(SupplierOutageGateIT.OutageTestConfig.class)
class SupplierOutageGateIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static final UUID EDITOR = UUID.fromString("00000000-0000-0000-0000-0000000ed170");

    @Autowired MockMvc mockMvc;
    @Autowired CatalogRepository catalogs;
    @Autowired CatalogHierarchyService hierarchy;

    @BeforeEach
    void resetStubs() {
        ToggleableRoleSupplier.outage = false;
        ProgrammableOpaClient.rule = ctx -> false;
        ProgrammableOpaClient.calls = 0;
    }

    @Test // I1 — THE CUT: a realm catalog-editor, supplier OUTAGE → 403, OPA never called, row unchanged
    void roleSourceOutage_denies403_neverReachesFallback() throws Exception {
        CatalogEntity catalog = seedCatalog();
        Integer versionBefore = catalogs.findById(catalog.getId()).orElseThrow().getVersion();
        ToggleableRoleSupplier.outage = true;
        // Even an allow-all policy must not be reached — the outage denies BEFORE any OPA call.
        ProgrammableOpaClient.rule = ctx -> true;

        mockMvc.perform(put("/api/v1/catalogs/{id}", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hacked-via-outage\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        CatalogEntity row = catalogs.findById(catalog.getId()).orElseThrow();
        assertThat(row.getName()).isEqualTo("outage-it-catalog"); // byte-identical — the handler never ran
        assertThat(row.getVersion()).isEqualTo(versionBefore);
        assertThat(ProgrammableOpaClient.calls)
                .as("the outage denied WITHOUT an OPA call — the realm fallback is never fed it")
                .isZero();
    }

    @Test // I2 — THE CONTRAST: same subject, authoritative no-role (Optional.empty()) → OPA IS reached
    // (here it grants), so the realm fallback decides exactly as before. B2 narrowed only the outage path.
    void authoritativeNoRole_reachesFallback_andGrants() throws Exception {
        CatalogEntity catalog = seedCatalog();
        ToggleableRoleSupplier.outage = false; // returns Optional.empty()
        ProgrammableOpaClient.rule = ctx -> true; // the (stubbed) realm fallback grants

        mockMvc.perform(put("/api/v1/catalogs/{id}", catalog.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed-by-fallback\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed-by-fallback"));

        assertThat(catalogs.findById(catalog.getId()).orElseThrow().getName())
                .isEqualTo("renamed-by-fallback"); // the fallback path applied the write
        assertThat(ProgrammableOpaClient.calls)
                .as("the empty no-role path STILL reaches OPA — the designed fallback path, unbroken")
                .isPositive();
    }

    private CatalogEntity seedCatalog() {
        CatalogEntity entity = new CatalogEntity(UUID.randomUUID(), "outage-it-catalog", null);
        hierarchy.assignPath(entity);
        return catalogs.save(entity);
    }

    @TestConfiguration
    static class OutageTestConfig {

        @Bean
        AbacSubjectExtractor outageSubjectExtractor() {
            // The subject carries realm catalog-editor — the realm role the pre-B2 fallback would widen.
            AbacContext.Subject editor = new AbacContext.Subject(
                    EDITOR.toString(), List.of("catalog-editor"), Map.of("username", "outage-editor"));
            return request -> Optional.of(editor);
        }

        @Bean
        RoleDefinitionSupplier toggleableRoleSupplier() {
            return new ToggleableRoleSupplier();
        }

        @Bean
        OpaClient programmableOpaClient() {
            return new ProgrammableOpaClient();
        }
    }

    /** A role supplier that simulates an OUTAGE (throw) vs an authoritative NO-ROLE (empty) by a flag. */
    static final class ToggleableRoleSupplier implements RoleDefinitionSupplier {
        static volatile boolean outage = false;

        @Override
        public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
            if (outage) {
                throw new RoleResolutionException("simulated user-management outage");
            }
            return Optional.empty(); // authoritative no-role → the realm fallback decides
        }
    }

    /** Counts every OPA call so I1 can prove the outage never reaches it and I2 that the empty path does. */
    static final class ProgrammableOpaClient implements OpaClient {
        static volatile java.util.function.Predicate<AbacContext> rule = ctx -> false;
        static volatile int calls = 0;
        static final List<AbacContext> captured = new CopyOnWriteArrayList<>();

        @Override
        public boolean allow(AbacContext context) {
            calls++;
            captured.add(context);
            return rule.test(context);
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.allowAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return Collections.nCopies(contexts.size(), Boolean.TRUE);
        }
    }
}
