package dev.dmitriikonovalov.opaabac.data.filter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.Condition;
import dev.dmitriikonovalov.opaabac.core.Conjunction;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.data.filter.ResidualSpecificationIT.FilterTestEntity;
import dev.dmitriikonovalov.opaabac.data.filter.ResidualSpecificationIT.FilterTestRepository;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test of {@link AbacQueryService} over real Postgres (QA case I2): two different
 * subjects, the same seeded table, the same list call → <strong>different row sets</strong>, with the
 * residual AND-ed with a caller scope. Reuses the {@link ResidualSpecificationIT} fixtures (entity, repo,
 * bootstrap app) so only the seam wiring is new here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
// Pin the configuration explicitly so @DataJpaTest does not package-scan for a @SpringBootConfiguration —
// the filter test package now holds more than one (HierarchyListFilterIT adds its own), which would be
// ambiguous. ContextConfiguration on the chosen TestApp both wires the beans and disables the scan.
@ContextConfiguration(classes = ResidualSpecificationIT.TestApp.class)
class AbacQueryServiceIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("queryservice")
            .withUsername("queryservice")
            .withPassword("queryservice");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private final ResidualSpecificationFactory factory = new ResidualSpecificationFactory();

    @Autowired
    private FilterTestRepository repository;

    private UUID emea;
    private UUID apac;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        emea = save(ResourceTags.fromMap(Map.of("region", "emea")));
        apac = save(ResourceTags.fromMap(Map.of("region", "apac")));
        save(ResourceTags.fromMap(Map.of("region", "amer")));
    }

    private UUID save(ResourceTags tags) {
        FilterTestEntity entity = new FilterTestEntity(UUID.randomUUID());
        entity.setTags(tags);
        return repository.saveAndFlush(entity).getId();
    }

    @Test // I2 — two subjects, same table, same call → different row sets
    void twoSubjects_getDifferentRowSets() {
        // Subject A is gated to region=emea; subject B to region=apac.
        OpaClient clientA = compileStub(residual("emea"));
        OpaClient clientB = compileStub(residual("apac"));
        AbacQueryService.PartialEvalSettings settings = AbacQueryService.PartialEvalSettings.defaults();

        List<UUID> aRows = new AbacQueryService(clientA, factory, settings)
                .findAuthorized(repository, Specification.where(null), ctx())
                .stream().map(FilterTestEntity::getId).toList();
        List<UUID> bRows = new AbacQueryService(clientB, factory, settings)
                .findAuthorized(repository, Specification.where(null), ctx())
                .stream().map(FilterTestEntity::getId).toList();

        assertThat(aRows).containsExactly(emea);
        assertThat(bRows).containsExactly(apac);
        assertThat(aRows).doesNotContainAnyElementsOf(bRows);
    }

    @Test // the residual is AND-ed with the caller scope (no cross-scope leak)
    void residual_isAndedWithScope() {
        // Subject is gated to region=emea, but the scope restricts to region=apac → intersection empty.
        OpaClient client = compileStub(residual("emea"));
        Specification<FilterTestEntity> scopeApac = (root, q, cb) ->
                cb.equal(cb.function("jsonb_extract_path_text", String.class, root.get("tags"), cb.literal("region")), "apac");

        List<UUID> rows = new AbacQueryService(client, factory, AbacQueryService.PartialEvalSettings.defaults())
                .findAuthorized(repository, scopeApac, ctx())
                .stream().map(FilterTestEntity::getId).toList();

        // emea (authz) AND apac (scope) → no row. Proves the AND, not a replace.
        assertThat(rows).isEmpty();
    }

    private AbacContext ctx() {
        return new AbacContext(
                new AbacContext.Subject("u", List.of(), Map.of()),
                "filter-test:read",
                new AbacContext.Resource("filter-test", null, Map.of()),
                null,
                Map.of());
    }

    private static PartialResult residual(String region) {
        return new PartialResult(
                PartialResult.Decision.CONDITIONAL,
                List.of(new Conjunction(List.of(new Condition("tags.region", Condition.Operator.EQ, region)))));
    }

    /** A stub client whose compile returns a fixed residual (fully supported); allow/allowAll unused. */
    private static OpaClient compileStub(PartialResult residual) {
        return new OpaClient() {
            @Override
            public boolean allow(AbacContext context) {
                return false;
            }

            @Override
            public PartialResult compile(AbacContext context) {
                return residual;
            }

            @Override
            public List<Boolean> allowAll(List<AbacContext> contexts) {
                return List.of();
            }
        };
    }
}
