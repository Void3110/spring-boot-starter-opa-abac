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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration proof of the <strong>paged</strong> {@link AbacQueryService#findAuthorized} seam against
 * real Postgres (Phase 5.95, QA cases I1–I4): the count is <em>subject-relative</em> (two subjects, same
 * data, different totals), a paged walk visits the authorized set exactly once (the determinism
 * regression test), the allowlist-fallback path pages the same sequence the pure-SQL path does, and a
 * past-the-end page is empty with the exact count. Reuses the {@link ResidualSpecificationIT} fixtures
 * (entity, repo, bootstrap app); every paged call carries the fixed total order
 * {@code createdAt ASC, id ASC} (ADR 0012 §4).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
// Pin the configuration explicitly so @DataJpaTest does not package-scan for a @SpringBootConfiguration —
// the filter test package holds more than one. ContextConfiguration on the shared TestApp wires the beans
// and disables the scan (the AbacQueryServiceIT model).
@ContextConfiguration(classes = ResidualSpecificationIT.TestApp.class)
class PaginationListIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("paginationtest")
            .withUsername("paginationtest")
            .withPassword("paginationtest");

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

    /** The fixed total order every paged endpoint uses (ADR 0012 §4): {@code createdAt ASC, id ASC}. */
    private static final Sort DEFAULT_ORDER =
            Sort.by("createdAt").ascending().and(Sort.by("id").ascending());

    private final ResidualSpecificationFactory factory = new ResidualSpecificationFactory();

    @Autowired
    private FilterTestRepository repository;

    private List<UUID> emeaIds;
    private List<UUID> apacIds;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        // Subject A's residual (region=emea) matches 5 rows; subject B's (region=apac) matches 3.
        emeaIds = new ArrayList<>();
        apacIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            emeaIds.add(save(ResourceTags.fromMap(Map.of("region", "emea"))));
        }
        for (int i = 0; i < 3; i++) {
            apacIds.add(save(ResourceTags.fromMap(Map.of("region", "apac"))));
        }
    }

    private UUID save(ResourceTags tags) {
        FilterTestEntity entity = new FilterTestEntity(UUID.randomUUID());
        entity.setTags(tags);
        return repository.saveAndFlush(entity).getId();
    }

    @Test // I1 — two subjects, same data, same paged call → different counts, disjoint contents
    void twoSubjects_sameData_differentCounts() {
        Page<FilterTestEntity> pageA = service(compileStub(residual("emea")))
                .findAuthorized(repository, Specification.where(null), ctx(), null, firstHundred());
        Page<FilterTestEntity> pageB = service(compileStub(residual("apac")))
                .findAuthorized(repository, Specification.where(null), ctx(), null, firstHundred());

        assertThat(pageA.getTotalElements()).isEqualTo(5);
        assertThat(pageB.getTotalElements()).isEqualTo(3);
        List<UUID> aRows = ids(pageA);
        List<UUID> bRows = ids(pageB);
        assertThat(aRows).containsExactlyInAnyOrderElementsOf(emeaIds);
        assertThat(bRows).containsExactlyInAnyOrderElementsOf(apacIds);
        assertThat(aRows).doesNotContainAnyElementsOf(bRows);
    }

    @Test // I2 — the stability walk: all pages at perPage=2 union to EXACTLY the authorized set,
    // no row repeated, none dropped, the count identical on every page (the determinism regression test)
    void stabilityWalk_perPage2_visitsAuthorizedSetExactlyOnce() {
        AbacQueryService svc = service(compileStub(residual("emea")));
        List<UUID> singlePage =
                ids(svc.findAuthorized(repository, Specification.where(null), ctx(), null, firstHundred()));

        List<UUID> walked = new ArrayList<>();
        Page<FilterTestEntity> page;
        int pageIndex = 0;
        do {
            page = svc.findAuthorized(
                    repository, Specification.where(null), ctx(), null,
                    PageRequest.of(pageIndex, 2, DEFAULT_ORDER));
            assertThat(page.getTotalElements()).isEqualTo(5); // the count is stable across pages
            walked.addAll(ids(page));
            pageIndex++;
        } while (page.hasNext());

        assertThat(pageIndex).isEqualTo(3); // 5 rows at perPage=2 → 3 pages
        assertThat(walked).hasSize(new LinkedHashSet<>(walked).size()); // no repeats
        assertThat(walked).containsExactlyElementsOf(singlePage); // same order, none dropped
        assertThat(walked).containsExactlyInAnyOrderElementsOf(emeaIds); // exactly the authorized set
    }

    @Test // I3 — fallback parity: the same grant shape behind an unsupported residual + allowlist pages
    // the same slices, the same count, the SAME ORDER as the pure-SQL path (path-independent contract)
    void fallbackPath_pagesSameSequenceAsPureSql() {
        AbacQueryService pureSql = service(compileStub(residual("emea")));
        AbacQueryService fallback = service(batchStub("emea"));

        for (int pageIndex = 0; pageIndex < 3; pageIndex++) {
            PageRequest window = PageRequest.of(pageIndex, 2, DEFAULT_ORDER);
            Page<FilterTestEntity> expected =
                    pureSql.findAuthorized(repository, Specification.where(null), ctx(), null, window);
            Page<FilterTestEntity> actual =
                    fallback.findAuthorized(repository, Specification.where(null), ctx(), null, window);

            assertThat(ids(actual)).containsExactlyElementsOf(ids(expected)); // slice AND order match
            assertThat(actual.getTotalElements()).isEqualTo(expected.getTotalElements()).isEqualTo(5);
        }
    }

    @Test // I4 — past-the-end: a page beyond the last → empty content, the exact count intact (both paths)
    void pastTheEnd_emptyContent_exactCount() {
        Page<FilterTestEntity> pureSql = service(compileStub(residual("emea")))
                .findAuthorized(repository, Specification.where(null), ctx(), null,
                        PageRequest.of(10, 2, DEFAULT_ORDER));
        Page<FilterTestEntity> fallback = service(batchStub("emea"))
                .findAuthorized(repository, Specification.where(null), ctx(), null,
                        PageRequest.of(10, 2, DEFAULT_ORDER));

        assertThat(pureSql.getContent()).isEmpty();
        assertThat(pureSql.getTotalElements()).isEqualTo(5);
        assertThat(fallback.getContent()).isEmpty();
        assertThat(fallback.getTotalElements()).isEqualTo(5);
    }

    // --- helpers -------------------------------------------------------------

    private AbacQueryService service(OpaClient client) {
        return new AbacQueryService(client, factory, AbacQueryService.PartialEvalSettings.defaults());
    }

    private static PageRequest firstHundred() {
        return PageRequest.of(0, 100, DEFAULT_ORDER);
    }

    private static List<UUID> ids(Page<FilterTestEntity> page) {
        return page.getContent().stream().map(FilterTestEntity::getId).toList();
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

    /** A stub client whose compile returns a fixed, fully-supported residual; allow/allowAll unused. */
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

    /**
     * A stub client forcing the allowlist-fallback path: compile reports a not-fully-SQL residual, and the
     * batch decides each row by its {@code region} tag — the same grant shape the pure-SQL residual
     * expresses, so I3 can compare the two paths' pages.
     */
    private static OpaClient batchStub(String grantedRegion) {
        return new OpaClient() {
            @Override
            public boolean allow(AbacContext context) {
                return false;
            }

            @Override
            public PartialResult compile(AbacContext context) {
                return PartialResult.unsupported();
            }

            @Override
            public List<Boolean> allowAll(List<AbacContext> contexts) {
                return contexts.stream()
                        .map(ctx -> grantedRegion.equals(ctx.resource().attributes().get("region")))
                        .toList();
            }
        };
    }
}
