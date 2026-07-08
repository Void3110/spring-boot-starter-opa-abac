package dev.dmitriikonovalov.opaabac.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.AncestorChainSupplier;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit cases U2–U9 for {@link ActionEnrichmentAdvice}: drives {@code beforeBodyWrite} directly with stub
 * {@link Enrichable} DTOs, a programmable {@link OpaClient} (positional {@code List<Boolean>}), a
 * pre-populated {@link AbacResourceCache} double, and a stub {@link RoleDefinitionSupplier}.
 */
class ActionEnrichmentAdviceTest {

    private static final List<String> CATEGORY_VERBS = List.of("view", "update", "delete", "assign-tags");

    private ProgrammableOpaClient opa;
    private MapResourceCache cache;
    private ActionEnrichmentAdvice advice;

    @BeforeEach
    void setUp() {
        opa = new ProgrammableOpaClient();
        cache = new MapResourceCache();
        // Default: a role always resolves; no hierarchy (flat — each resource is its own root).
        RoleDefinitionSupplier roles = (userId, type, id) ->
                Optional.of(new RoleDefinition("role", Map.of(), Map.of()));
        advice = new ActionEnrichmentAdvice(opa, cache, roles, null);
        SecurityContextHolder.getContext().setAuthentication(
                new AbacAuthentication(new AbacContext.Subject("user-1", List.of("viewer"), Map.of())));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- U2: non-Enrichable passthrough ---------------------------------------------------------

    @Test // U2 — a non-Enrichable return passes through untouched, no OPA call
    void nonEnrichableBody_passesThrough() {
        Object body = "just a string";
        Object result = invoke(body);
        assertThat(result).isSameAs(body);
        assertThat(opa.calls).isZero();
    }

    @Test // U2 — a null body passes through, no OPA call
    void nullBody_passesThrough() {
        assertThat(invoke(null)).isNull();
        assertThat(opa.calls).isZero();
    }

    // ---- U3: the P×V refold ---------------------------------------------------------------------

    @Test // U3 — 3-row page, V=4, a known 12-bool list folds to the right per-row maps
    void pxvRefold_mapsEachRowToItsVerdicts() {
        StubCategory a = cached("a");
        StubCategory b = cached("b");
        StubCategory c = cached("c");
        // row a: view,update,delete,assign-tags = T,T,F,T ; row b: T,F,F,F ; row c: T,T,T,T
        opa.respond(ctx -> List.of(
                true, true, false, true,
                true, false, false, false,
                true, true, true, true));
        invoke(new StubPage(List.of(a, b, c)));

        assertThat(a.getActions()).containsExactlyEntriesOf(map("view", true, "update", true, "delete", false, "assign-tags", true));
        assertThat(b.getActions()).containsExactlyEntriesOf(map("view", true, "update", false, "delete", false, "assign-tags", false));
        assertThat(c.getActions()).containsExactlyEntriesOf(map("view", true, "update", true, "delete", true, "assign-tags", true));
        assertThat(opa.calls).isEqualTo(1); // one bulk for the one type
    }

    // ---- U4: single resource, all allowed -------------------------------------------------------

    @Test // U4 — single Enrichable, cache hit, all verbs allowed; one allowAll with V contexts
    void singleResource_allAllowed() {
        StubCategory cat = cached("a");
        opa.respond(ctx -> List.of(true, true, true, true));
        invoke(cat);

        assertThat(cat.getActions()).containsExactlyEntriesOf(
                map("view", true, "update", true, "delete", true, "assign-tags", true));
        assertThat(opa.lastContexts).hasSize(4);
        // every context carries the re-qualified "type:verb" action and the resource (type,id,attributes)
        assertThat(opa.lastContexts).extracting(AbacContext::action)
                .containsExactly("category:view", "category:update", "category:delete", "category:assign-tags");
        assertThat(opa.lastContexts.get(0).resource().type()).isEqualTo("category");
        assertThat(opa.lastContexts.get(0).resource().id()).isEqualTo(cat.getId().toString());
        assertThat(opa.lastContexts.get(0).resource().attributes()).containsEntry("region", "emea");
    }

    // ---- U5: honest false (the headline) --------------------------------------------------------

    @Test // U5 — denies update/delete/assign-tags, allows view → a complete map with real denials
    void honestFalse_completeMapWithRealDenials() {
        StubCategory cat = cached("a");
        opa.respond(ctx -> List.of(true, false, false, false));
        invoke(cat);

        assertThat(cat.getActions()).containsExactlyEntriesOf(
                map("view", true, "update", false, "delete", false, "assign-tags", false));
    }

    // ---- U6: bulk failure → omit (never all-false) ----------------------------------------------

    @Test // U6 — allowAll throws → the row's _actions stays unset; no fabricated map
    void bulkThrows_omits() {
        StubCategory cat = cached("a");
        opa.respondThrowing();
        invoke(cat);
        assertThat(cat.getActions()).isNull();
    }

    @Test // U6 — allowAll returns a short/mismatched list → omit all
    void bulkShortList_omits() {
        StubCategory cat = cached("a");
        opa.respond(ctx -> List.of(true, true)); // expected 4, got 2
        invoke(cat);
        assertThat(cat.getActions()).isNull();
    }

    @Test // U6 — allowAll returns all-false (indistinguishable from a transport-error degrade) → omit
    void bulkAllFalse_omits() {
        StubCategory cat = cached("a");
        opa.respond(ctx -> List.of(false, false, false, false));
        invoke(cat);
        assertThat(cat.getActions())
                .as("an all-false block is treated as could-not-compute and omitted, never emitted")
                .isNull();
    }

    // ---- U7: cache miss for one row of a page → that row omitted, others enriched ----------------

    @Test // U7 — middle row missing from the cache; the other two still enrich
    void cacheMissForOneRow_omitsOnlyThatRow() {
        StubCategory a = cached("a");
        StubCategory b = uncached("b"); // not put into the cache
        StubCategory c = cached("c");
        // batch carries only a and c (2 rows × 4 verbs = 8 contexts)
        opa.respond(ctx -> List.of(
                true, true, true, true,   // a
                true, false, false, false)); // c
        invoke(new StubPage(List.of(a, b, c)));

        assertThat(a.getActions()).isNotNull();
        assertThat(b.getActions()).as("the cache-missed row is omitted").isNull();
        assertThat(c.getActions()).isNotNull();
        assertThat(opa.lastContexts).hasSize(8); // only the two cached rows were batched
    }

    // ---- U8: ancestor / role failure for a row → omit -------------------------------------------

    @Test // U8 — the ancestor supplier throws for a row → that row omitted (never partial)
    void ancestorSupplierThrows_omits() {
        AncestorChainSupplier throwing = (type, id) -> {
            throw new IllegalStateException("ancestor source down");
        };
        advice = new ActionEnrichmentAdvice(opa, cache,
                (u, t, i) -> Optional.of(new RoleDefinition("r", Map.of(), Map.of())), throwing);
        StubCategory cat = cached("a");
        opa.respond(ctx -> List.of(true, true, true, true));
        invoke(cat);
        assertThat(cat.getActions()).isNull();
        assertThat(opa.calls).as("no batch issued when every row failed preparation").isZero();
    }

    @Test // U8 — a RoleResolutionException (role-source outage) → omit that row
    void roleResolutionOutage_omits() {
        RoleDefinitionSupplier outage = (u, t, i) -> {
            throw new RoleResolutionException("role source unavailable");
        };
        advice = new ActionEnrichmentAdvice(opa, cache, outage, null);
        StubCategory cat = cached("a");
        opa.respond(ctx -> List.of(true, true, true, true));
        invoke(cat);
        assertThat(cat.getActions()).isNull();
    }

    // ---- U9: the cache is a snapshot, not a verdict ---------------------------------------------

    @Test // U9 — a cached instance never short-circuits to "allowed"; a fresh allowAll always runs
    void cacheIsSnapshotNotVerdict() {
        StubCategory cat = cached("a");
        // even with the instance cached, the verdict comes only from allowAll (here: deny update)
        opa.respond(ctx -> List.of(true, false, false, false));
        invoke(cat);
        assertThat(opa.calls).as("presence in the cache never substitutes for a fresh decision").isEqualTo(1);
        assertThat(cat.getActions().get("update")).isFalse();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private Object invoke(Object body) {
        return advice.beforeBodyWrite(body, null, null, null, null, null);
    }

    private StubCategory cached(String idSuffix) {
        StubCategory dto = new StubCategory(uuid(idSuffix));
        cache.put("category", dto.getId().toString(), new ResolvedCategory(dto.getId().toString()));
        return dto;
    }

    private StubCategory uncached(String idSuffix) {
        return new StubCategory(uuid(idSuffix));
    }

    private static UUID uuid(String suffix) {
        return UUID.nameUUIDFromBytes(("cat-" + suffix).getBytes());
    }

    private static Map<String, Boolean> map(Object... kv) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Boolean) kv[i + 1]);
        }
        return m;
    }

    // ---- stubs ----------------------------------------------------------------------------------

    /** A test category DTO implementing the per-type sub-interface contract directly. */
    static final class StubCategory implements Enrichable {
        private final UUID id;
        private Map<String, Boolean> actions;

        StubCategory(UUID id) {
            this.id = id;
        }

        @Override public UUID getId() { return id; }
        @Override public Map<String, Boolean> getActions() { return actions; }
        @Override public void setActions(Map<String, Boolean> actions) { this.actions = actions; }
        @Override public String abacResourceType() { return "category"; }
        @Override public List<String> abacActions() { return CATEGORY_VERBS; }
    }

    /** A paged envelope (the ADR-0012 shape): a no-arg getItems() returning a List. */
    static final class StubPage {
        private final List<StubCategory> items;

        StubPage(List<StubCategory> items) {
            this.items = items;
        }

        public List<StubCategory> getItems() {
            return items;
        }
    }

    /** The resolved snapshot the cache hands back (carries the attributes the verdict sees). */
    record ResolvedCategory(String id) implements AbacResource {
        @Override public String abacResourceType() { return "category"; }
        @Override public String abacResourceId() { return id; }
        @Override public Map<String, Object> abacAttributes() { return Map.of("region", "emea"); }
    }

    /** A request-attributes-free cache double. */
    static final class MapResourceCache implements AbacResourceCache {
        private final Map<String, Object> store = new LinkedHashMap<>();

        @Override
        public <T> Optional<T> get(String type, String id, Class<T> as) {
            Object v = store.get(key(type, id));
            return as.isInstance(v) ? Optional.of(as.cast(v)) : Optional.empty();
        }

        @Override
        public void put(String type, String id, Object resource) {
            store.put(key(type, id), resource);
        }

        private static String key(String type, String id) {
            return type + ":" + id;
        }
    }

    /** A programmable OpaClient: records contexts, returns a positional list (or throws) on demand. */
    static final class ProgrammableOpaClient implements OpaClient {
        int calls;
        List<AbacContext> lastContexts = List.of();
        private Function<List<AbacContext>, List<Boolean>> responder = ctx -> List.of();
        private boolean throwing;

        void respond(Function<List<AbacContext>, List<Boolean>> responder) {
            this.responder = responder;
        }

        void respondThrowing() {
            this.throwing = true;
        }

        @Override
        public boolean allow(AbacContext context) {
            throw new UnsupportedOperationException("enrichment never calls allow()");
        }

        @Override
        public PartialResult compile(AbacContext context) {
            throw new UnsupportedOperationException("enrichment never calls compile()");
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            calls++;
            lastContexts = new ArrayList<>(contexts);
            if (throwing) {
                throw new IllegalStateException("bulk transport failure");
            }
            return responder.apply(contexts);
        }
    }
}
