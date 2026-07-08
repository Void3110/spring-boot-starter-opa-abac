package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.openapi.model.Team;
import dev.dmitriikonovalov.example.usermgmt.security.TeamEnrichable;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import dev.dmitriikonovalov.opaabac.security.web.ActionEnrichmentAdvice;
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
 * Covers the <strong>populated</strong> team {@code _actions} path (QA I7, first clause) — the positive
 * counterpart to {@link ActionEnrichmentIT}'s ungated-degrade cell. In the current app a {@code Team} is
 * only ever returned by an <em>ungated</em> read (no gate write-throughs it into the cache), so the live
 * map is always absent (the documented degrade); this drives {@link ActionEnrichmentAdvice} directly with
 * a <em>pre-populated</em> cache to prove the otherwise-unreachable positive path: the real generated
 * {@code Team} DTO (which {@code implements TeamEnrichable}) + the real team verb set
 * ({@code [list-members, add-member, remove-member]}) refold into a complete, honest map, and the
 * Java-co-gated escalation verbs never appear. No Spring context — the advice mechanics in isolation.
 */
class TeamEnrichmentAdviceTest {

    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private ProgrammableOpaClient opa;
    private MapResourceCache cache;
    private ActionEnrichmentAdvice advice;

    @BeforeEach
    void setUp() {
        opa = new ProgrammableOpaClient();
        cache = new MapResourceCache();
        RoleDefinitionSupplier roles = (userId, type, id) ->
                Optional.of(new RoleDefinition("role", Map.of(), Map.of()));
        advice = new ActionEnrichmentAdvice(opa, cache, roles, null); // flat: team is its own governing root
        SecurityContextHolder.getContext().setAuthentication(
                new AbacAuthentication(new AbacContext.Subject(SUBJECT.toString(), List.of("member"), Map.of())));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test // I7 (populated) — a cached team enriches with the OPA-decided subset; a member: list true, mutating false
    void populatedTeamMapIsHonestAndSubsetOnly() {
        Team team = new Team().id(UUID.randomUUID());
        cache.put("team", team.getId().toString(), new ResolvedTeam(team.getId().toString()));
        // a member can list members (READ) but not add/remove (CONTROL) — the honest split
        opa.respond(ctx -> List.of(true, false, false));

        advice.beforeBodyWrite(team, null, null, null, null, null);

        assertThat(team.getActions())
                .as("the team verb set is exactly the OPA-decided subset, with real per-verb verdicts")
                .containsExactlyEntriesOf(orderedMap(
                        "list-members", true, "add-member", false, "remove-member", false));
        assertThat(team.getActions().keySet())
                .as("the Java-co-gated escalation verbs are never enumerated (affordance honesty)")
                .doesNotContain("change-role", "define-roles", "transfer-ownership");
        // the advice asked OPA for exactly the three subset verbs, re-qualified to team:<verb>
        assertThat(opa.lastContexts).extracting(AbacContext::action)
                .containsExactly("team:list-members", "team:add-member", "team:remove-member");
    }

    @Test // sanity: the generated Team DTO really carries the TeamEnrichable contract the advice reads
    void generatedTeamImplementsTheMarkerWithTheVerifiedVerbSet() {
        Team team = new Team().id(UUID.randomUUID());
        assertThat(team).isInstanceOf(TeamEnrichable.class);
        TeamEnrichable marker = team;
        assertThat(marker.abacResourceType()).isEqualTo("team");
        assertThat(marker.abacActions()).containsExactly("list-members", "add-member", "remove-member");
    }

    private static Map<String, Boolean> orderedMap(Object... kv) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Boolean) kv[i + 1]);
        }
        return m;
    }

    /** The resolved snapshot the cache hands back for a team. */
    record ResolvedTeam(String id) implements AbacResource {
        @Override public String abacResourceType() { return "team"; }
        @Override public String abacResourceId() { return id; }
        @Override public Map<String, Object> abacAttributes() { return Map.of(); }
    }

    /** A request-attributes-free cache double. */
    static final class MapResourceCache implements AbacResourceCache {
        private final Map<String, Object> store = new LinkedHashMap<>();

        @Override
        public <T> Optional<T> get(String type, String id, Class<T> as) {
            Object v = store.get(type + ":" + id);
            return as.isInstance(v) ? Optional.of(as.cast(v)) : Optional.empty();
        }

        @Override
        public void put(String type, String id, Object resource) {
            store.put(type + ":" + id, resource);
        }
    }

    /** A programmable OpaClient recording the contexts it was asked to decide. */
    static final class ProgrammableOpaClient implements OpaClient {
        List<AbacContext> lastContexts = List.of();
        private Function<List<AbacContext>, List<Boolean>> responder = ctx -> List.of();

        void respond(Function<List<AbacContext>, List<Boolean>> responder) {
            this.responder = responder;
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
            lastContexts = new ArrayList<>(contexts);
            return responder.apply(contexts);
        }
    }
}
