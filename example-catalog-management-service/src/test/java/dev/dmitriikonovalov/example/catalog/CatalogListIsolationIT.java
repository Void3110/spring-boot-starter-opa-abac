package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.catalog.config.CatalogListAuthorizer;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.data.filter.GovernedScopeResolver;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Slice B4 catalog-list isolation ITs (I1–I3) against <b>real Postgres</b>: the governed base scope is a
 * real {@code id IN (…)} pushed into SQL, so two subjects with different governed sets see <b>different
 * rows</b>, a subject governing none sees an empty page, and a multi-team subject sees the union.
 *
 * <p>OPA is a programmable in-process stub (mirroring {@code ActionEnrichmentListIT}): {@code compile}
 * returns {@link PartialResult#allowAll()} for the role-grants-{@code list} case (so the governed scope is
 * the only cut) and a deny-all residual for the role-denies-{@code list} case ({@code governedScope ∧
 * DENY_ALL = empty}). The membership cut itself is the {@link GovernedScopeResolver}, here a test bean
 * returning per-subject governed ids — the real user-service join is proven in {@code EffectiveRoleResolveIT}
 * (T3) and end-to-end in T9.
 */
@Import(CatalogListIsolationIT.IsolationTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = "catalog.role-source=none")
class CatalogListIsolationIT extends AbstractPostgresIT {

    private static final String ALICE = "sub-alice";
    private static final String BOB = "sub-bob";
    private static final String CAROL = "sub-carol";
    private static final String NOBODY = "sub-nobody";

    @Autowired private CatalogListAuthorizer authorizer;
    @Autowired private CatalogRepository catalogs;

    private final Pageable pageable =
            PageRequest.of(0, 50, Sort.by("createdAt").ascending().and(Sort.by("id")));

    private UUID aliceCatalog;
    private UUID bobCatalog;
    private UUID carolCatalog;

    @BeforeEach
    void seed() {
        catalogs.deleteAll();
        TestGovernedScopeResolver.governed.clear();
        StubOpaClient.compileAllowAll = true;

        aliceCatalog = saveCatalog("Alice Co");
        bobCatalog = saveCatalog("Bob Co");
        carolCatalog = saveCatalog("Carol Co");
        // A fourth catalog governed by nobody in these tests — the "leak" row a broken scope would surface.
        saveCatalog("Unowned Co");

        // Membership: Alice governs hers; Bob governs his; Carol is multi-team (her own + Alice's).
        TestGovernedScopeResolver.governed.put(ALICE, List.of(aliceCatalog));
        TestGovernedScopeResolver.governed.put(BOB, List.of(bobCatalog));
        TestGovernedScopeResolver.governed.put(CAROL, List.of(carolCatalog, aliceCatalog));
        // NOBODY governs nothing (absent from the map → empty list).
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private UUID saveCatalog(String name) {
        CatalogEntity c = new CatalogEntity(UUID.randomUUID(), name, name + " description");
        return catalogs.save(c).getId();
    }

    private Page<CatalogEntity> listAs(String subject) {
        SecurityContextHolder.getContext().setAuthentication(
                new AbacAuthentication(new AbacContext.Subject(subject, List.of(), Map.of())));
        return authorizer.readable(pageable);
    }

    private static List<UUID> ids(Page<CatalogEntity> page) {
        return page.getContent().stream().map(CatalogEntity::getId).toList();
    }

    @Test // I1 — two subjects with different governed sets see DIFFERENT rows (the residual is real SQL)
    void differentSubjectsSeeDifferentRows() {
        Page<CatalogEntity> aliceList = listAs(ALICE);
        Page<CatalogEntity> bobList = listAs(BOB);

        assertThat(ids(aliceList)).containsExactly(aliceCatalog);
        assertThat(ids(bobList)).containsExactly(bobCatalog);
        assertThat(ids(aliceList)).doesNotContainAnyElementsOf(ids(bobList)); // disjoint — no leak
        // Neither sees the unowned catalog (4 rows exist; each sees only their one).
        assertThat(aliceList.getTotalElements()).isEqualTo(1);
        assertThat(bobList.getTotalElements()).isEqualTo(1);
    }

    @Test // I2 — a subject governing NOTHING → empty page (fail-closed, not the whole table)
    void governsNothingSeesEmptyPage() {
        Page<CatalogEntity> page = listAs(NOBODY);
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        // Sanity: there ARE catalogs in the table — the empty page is the cut, not an empty DB.
        assertThat(catalogs.count()).isEqualTo(4);
    }

    @Test // I3 — a multi-team subject sees the UNION of their governed catalogs, exact count
    void multiTeamSubjectSeesUnion() {
        Page<CatalogEntity> page = listAs(CAROL);
        assertThat(ids(page)).containsExactlyInAnyOrder(carolCatalog, aliceCatalog);
        assertThat(page.getTotalElements()).isEqualTo(2);
        // Carol does NOT see Bob's or the unowned catalog.
        assertThat(ids(page)).doesNotContain(bobCatalog);
    }

    @Test // the role-denies-list path: governedScope ∧ DENY_ALL residual = empty (fail-closed)
    void roleDenyingListResidualYieldsEmpty() {
        StubOpaClient.compileAllowAll = false; // compile → deny-all residual
        Page<CatalogEntity> page = listAs(ALICE); // Alice governs a catalog, but her role denies list
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    // --- test doubles --------------------------------------------------------------------------------

    @TestConfiguration
    static class IsolationTestConfig {

        @Bean
        GovernedScopeResolver testGovernedScopeResolver() {
            return new TestGovernedScopeResolver();
        }

        @Bean
        @org.springframework.context.annotation.Primary
        OpaClient stubOpaClient() {
            // @Primary so it wins over PermissiveSecurityTestConfig's allowAllOpaClient (imported via
            // AbstractPostgresIT) — we need to CONTROL the compile residual (allow-all vs deny-all) to
            // prove the role-denies-list path, not just always-allow.
            return new StubOpaClient();
        }

        @Bean
        RoleDefinitionSupplier listRoleSupplier() {
            // Any non-empty role drives the residual; the StubOpaClient decides allow-all vs deny-all. A
            // resolved role is what the authorizer needs to proceed past the role lookup on the first
            // governed id.
            return (userId, type, id) -> Optional.of(
                    new RoleDefinition("member", Map.of(), Map.of("catalog", List.of("READ"))));
        }
    }

    /** Per-subject governed ids, set per test. Implements the SPI's primitive; scope comes from the default. */
    static final class TestGovernedScopeResolver implements GovernedScopeResolver {
        static final Map<String, List<UUID>> governed = new ConcurrentHashMap<>();

        @Override
        public List<UUID> governedIds(String subject, String resourceType) {
            return governed.getOrDefault(subject, List.of());
        }
    }

    /** compile → ALLOW_ALL (the governed scope is the only cut) or a deny-all residual, per the flag. */
    static final class StubOpaClient implements OpaClient {
        static volatile boolean compileAllowAll = true;

        @Override
        public boolean allow(AbacContext context) {
            return true;
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return compileAllowAll ? PartialResult.allowAll() : PartialResult.denyAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return contexts.stream().map(c -> true).toList();
        }
    }
}
