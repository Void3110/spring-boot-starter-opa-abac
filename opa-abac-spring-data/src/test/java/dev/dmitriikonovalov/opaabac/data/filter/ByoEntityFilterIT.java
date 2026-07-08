package dev.dmitriikonovalov.opaabac.data.filter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.Condition;
import dev.dmitriikonovalov.opaabac.core.Conjunction;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTags;
import dev.dmitriikonovalov.opaabac.data.model.ResourceTagsConverter;
import dev.dmitriikonovalov.opaabac.data.model.Taggable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The <strong>bring-your-own-entity</strong> contract proof (pre-publish API polish): the data-filter
 * must work for an entity that does <em>not</em> extend {@code AbstractSecuredEntity} — it only
 * implements the interfaces ({@link AbacResource} + {@link Taggable}) and maps its own JSONB tags
 * column. The load-bearing cut: the filter addresses the JPA <em>attribute</em>
 * {@link Taggable#TAGS_ATTRIBUTE}, never the DB column — so this fixture deliberately maps the
 * attribute {@code tags} onto a differently-named column ({@code custom_tags}). Before the
 * {@code TAGS_ATTRIBUTE} extraction the attribute name was a hardcoded literal at two sites; an
 * external consumer implementing the interfaces compiled clean and failed at query time. This IT
 * pins both sites through the BYO mapping:
 *
 * <ul>
 *   <li>{@link ResidualSpecificationFactory} — the residual's tag path (site 1);</li>
 *   <li>{@link AbacQueryService}'s deny-override guard inside {@code findAuthorized} (site 2) —
 *       a row carrying {@code abac_deny: "true"} must drop out of the pure-SQL list.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@org.springframework.test.context.ContextConfiguration(classes = ByoEntityFilterIT.TestApp.class)
class ByoEntityFilterIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("byotest")
            .withUsername("byotest")
            .withPassword("byotest");

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
    private ByoRepository repository;

    private UUID emea;
    private UUID apac;
    private UUID deniedEmea;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        emea = save(Map.of("region", "emea"));
        apac = save(Map.of("region", "apac"));
        deniedEmea = save(Map.of("region", "emea", "abac_deny", "true"));
    }

    private UUID save(Map<String, Object> tags) {
        ByoTaggedEntity entity = new ByoTaggedEntity(UUID.randomUUID());
        entity.setTags(ResourceTags.fromMap(tags));
        return repository.saveAndFlush(entity).getId();
    }

    @Test // Site 1: the residual factory reads the ATTRIBUTE, not the column — BYO mapping filters in SQL.
    void residualFactory_filtersByoEntityThroughTheAttributeName() {
        PartialResult residual = new PartialResult(
                PartialResult.Decision.CONDITIONAL,
                List.of(new Conjunction(List.of(new Condition("tags.region", Condition.Operator.EQ, "emea")))));
        Specification<ByoTaggedEntity> spec = factory.from(residual);
        assertThat(repository.findAll(spec).stream().map(ByoTaggedEntity::getId))
                .containsExactlyInAnyOrder(emea, deniedEmea);
    }

    @Test // Site 2: findAuthorized's deny-override guard resolves through the attribute on the BYO entity.
    void findAuthorized_pureSqlPath_appliesTheDenyOverrideOnTheByoEntity() {
        PartialResult residual = new PartialResult(
                PartialResult.Decision.CONDITIONAL,
                List.of(new Conjunction(List.of(new Condition("tags.region", Condition.Operator.EQ, "emea")))));
        AbacQueryService service = new AbacQueryService(
                stubOpaClient(residual),
                factory,
                AbacQueryService.PartialEvalSettings.defaults());

        List<ByoTaggedEntity> rows =
                service.findAuthorized(repository, Specification.where(null), queryContext());

        // The emea residual matches two rows; the abac_deny row must drop (site 2 through custom_tags).
        assertThat(rows.stream().map(ByoTaggedEntity::getId)).containsExactly(emea);
        assertThat(rows.stream().map(ByoTaggedEntity::getId)).doesNotContain(deniedEmea, apac);
    }

    private static AbacContext queryContext() {
        return new AbacContext(
                new AbacContext.Subject("byo-subject", List.of("byo-role"), Map.of()),
                "byo:list",
                new AbacContext.Resource("byo", null, Map.of()),
                Map.of());
    }

    /** A stub OPA client: compile answers the given residual; nothing else is exercised. */
    private static OpaClient stubOpaClient(PartialResult residual) {
        return new OpaClient() {
            @Override
            public boolean allow(AbacContext context) {
                return true;
            }

            @Override
            public PartialResult compile(AbacContext context) {
                return residual;
            }

            @Override
            public List<Boolean> allowAll(List<AbacContext> contexts) {
                return Collections.nCopies(contexts.size(), Boolean.TRUE);
            }
        };
    }

    // --- the BYO fixture: interfaces only, own id, own COLUMN name ------------------------------

    @Entity
    @Table(name = "byo_tagged_entity")
    static class ByoTaggedEntity implements AbacResource, Taggable {

        @Id
        private UUID id;

        // The contract under test: JPA attribute = Taggable.TAGS_ATTRIBUTE ("tags"), DB column = anything.
        @Convert(converter = ResourceTagsConverter.class)
        @JdbcTypeCode(SqlTypes.JSON)
        @Column(name = "custom_tags", columnDefinition = "jsonb", nullable = false)
        private ResourceTags tags = ResourceTags.empty();

        protected ByoTaggedEntity() {
            // JPA
        }

        ByoTaggedEntity(UUID id) {
            this.id = id;
        }

        UUID getId() {
            return id;
        }

        @Override
        public String abacResourceType() {
            return "byo";
        }

        @Override
        public String abacResourceId() {
            return id.toString();
        }

        @Override
        public ResourceTags getTags() {
            return tags;
        }

        @Override
        public void setTags(ResourceTags tags) {
            this.tags = tags;
        }
    }

    interface ByoRepository
            extends JpaRepository<ByoTaggedEntity, UUID>, JpaSpecificationExecutor<ByoTaggedEntity> {}

    @SpringBootApplication
    @EntityScan(basePackageClasses = ByoTaggedEntity.class)
    @EnableJpaRepositories(considerNestedRepositories = true)
    static class TestApp {}
}
