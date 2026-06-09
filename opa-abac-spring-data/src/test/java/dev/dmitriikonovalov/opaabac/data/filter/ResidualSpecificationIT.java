package dev.dmitriikonovalov.opaabac.data.filter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.Condition;
import dev.dmitriikonovalov.opaabac.core.Conjunction;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.data.model.AbstractSecuredEntity;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link ResidualSpecificationFactory} against a <strong>real Postgres + JSONB</strong>
 * (never H2 — the {@code jsonb_*} functions and the {@code ?} existence operator are Postgres-specific).
 * Seeds rows with differing JSONB tags, runs the <em>generated</em> {@link Specification} for {@code EQ},
 * {@code IN}, {@code CONTAINS}, and a DNF residual, and asserts the <strong>exact surviving row set</strong>
 * each time — including that {@code CONTAINS} matches the array-valued tag (QA case I1).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
// Pin the configuration explicitly: the filter test package now holds more than one
// @SpringBootConfiguration (HierarchyListFilterIT adds its own), so @DataJpaTest's package scan would be
// ambiguous. ContextConfiguration on this IT's own TestApp wires its beans and disables that scan.
@org.springframework.test.context.ContextConfiguration(classes = ResidualSpecificationIT.TestApp.class)
class ResidualSpecificationIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("filtertest")
            .withUsername("filtertest")
            .withPassword("filtertest");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Let Hibernate create the single test table — no Liquibase in this module's test scope.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private final ResidualSpecificationFactory factory = new ResidualSpecificationFactory();

    @Autowired
    private FilterTestRepository repository;

    private UUID emea;
    private UUID apac;
    private UUID multiRegion;
    private UUID publicSensitivity;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        emea = save(ResourceTags.fromMap(java.util.Map.of("region", "emea")));
        apac = save(ResourceTags.fromMap(java.util.Map.of("region", "apac")));
        multiRegion = save(ResourceTags.fromMap(java.util.Map.of("region", List.of("emea", "amer"))));
        publicSensitivity = save(ResourceTags.fromMap(java.util.Map.of("sensitivity", "public")));
    }

    private UUID save(ResourceTags tags) {
        FilterTestEntity entity = new FilterTestEntity(UUID.randomUUID());
        entity.setTags(tags);
        return repository.saveAndFlush(entity).getId();
    }

    @Test // I1 — EQ on a scalar tag: region == "emea" → only the scalar-emea row
    void eq_matchesScalarTag() {
        PartialResult residual = conditional(new Condition("tags.region", Condition.Operator.EQ, "emea"));
        assertThat(idsMatching(residual)).containsExactlyInAnyOrder(emea);
    }

    @Test // I1 — IN over a scalar tag: region in {emea,apac} → both scalar rows (not the array row)
    void in_matchesScalarTag() {
        PartialResult residual =
                conditional(new Condition("tags.region", Condition.Operator.IN, List.of("emea", "apac")));
        assertThat(idsMatching(residual)).containsExactlyInAnyOrder(emea, apac);
    }

    @Test // I1 — CONTAINS on an array tag: region ∋ "amer" → only the array row
    void contains_matchesArrayTag() {
        PartialResult residual =
                conditional(new Condition("tags.region", Condition.Operator.CONTAINS, "amer"));
        assertThat(idsMatching(residual)).containsExactlyInAnyOrder(multiRegion);
    }

    @Test // I1 — CONTAINS for a shared element: region ∋ "emea" → BOTH the array row and the scalar row
    void contains_matchesScalarAndArray_forSharedElement() {
        // Policy-consistency: the single-decision Rego (resource_tag_values) normalizes a SCALAR tag to a
        // singleton set, so a scalar region=="emea" satisfies a membership grant exactly as the array does.
        // The Postgres `?` operator agrees: on a JSONB string it tests string equality, on an array it tests
        // element membership — so jsonb_exists matches BOTH the scalar-emea row and the [emea,amer] row.
        // This is the SQL/Rego agreement that keeps the list and a single-GET deciding the same rows.
        PartialResult residual =
                conditional(new Condition("tags.region", Condition.Operator.CONTAINS, "emea"));
        assertThat(idsMatching(residual)).containsExactlyInAnyOrder(emea, multiRegion);
    }

    @Test // I1 — DNF: region==apac OR sensitivity==public → the apac row and the public row
    void dnf_matchesUnion() {
        PartialResult residual = new PartialResult(
                PartialResult.Decision.CONDITIONAL,
                List.of(
                        new Conjunction(List.of(new Condition("tags.region", Condition.Operator.EQ, "apac"))),
                        new Conjunction(
                                List.of(new Condition("tags.sensitivity", Condition.Operator.EQ, "public")))));
        assertThat(idsMatching(residual)).containsExactlyInAnyOrder(apac, publicSensitivity);
    }

    @Test // ALLOW_ALL → all rows; DENY_ALL → none
    void allowAll_andDenyAll() {
        assertThat(idsMatching(PartialResult.allowAll()))
                .containsExactlyInAnyOrder(emea, apac, multiRegion, publicSensitivity);
        assertThat(idsMatching(PartialResult.denyAll())).isEmpty();
    }

    private List<UUID> idsMatching(PartialResult residual) {
        Specification<FilterTestEntity> spec = factory.from(residual);
        return repository.findAll(spec).stream().map(FilterTestEntity::getId).toList();
    }

    private static PartialResult conditional(Condition condition) {
        return new PartialResult(
                PartialResult.Decision.CONDITIONAL, List.of(new Conjunction(List.of(condition))));
    }

    // --- test fixtures -------------------------------------------------------

    @Entity
    @Table(name = "filter_test_entity")
    static class FilterTestEntity extends AbstractSecuredEntity {
        FilterTestEntity() {
            // JPA
        }

        FilterTestEntity(UUID id) {
            super(id);
        }

        @Override
        public String abacResourceType() {
            return "filter-test";
        }
    }

    interface FilterTestRepository
            extends JpaRepository<FilterTestEntity, UUID>, JpaSpecificationExecutor<FilterTestEntity> {}

    @SpringBootApplication
    @EntityScan(basePackageClasses = FilterTestEntity.class)
    @EnableJpaRepositories(considerNestedRepositories = true)
    // Auditing populates the not-null createdAt; the DateTimeProvider yields OffsetDateTime to match the
    // entity's timestamptz columns (the LocalDateTime-vs-OffsetDateTime auditing gotcha).
    @EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
    static class TestApp {

        @TestConfiguration
        static class Beans {
            @Bean
            ResidualSpecificationFactory residualSpecificationFactory() {
                return new ResidualSpecificationFactory();
            }

            @Bean
            DateTimeProvider auditingDateTimeProvider() {
                return () -> java.util.Optional.of(java.time.OffsetDateTime.now());
            }

            @Bean
            AuditorAware<UUID> auditorAware() {
                return java.util.Optional::empty;
            }
        }
    }
}
