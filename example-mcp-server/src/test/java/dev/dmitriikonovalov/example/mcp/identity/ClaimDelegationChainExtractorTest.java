package dev.dmitriikonovalov.example.mcp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * U5–U12: the whole extractor contract. The two shapes that must <em>not</em> be confused are U7 (absent
 * claim → an honest human call) and U8–U12 (malformed claim → deny). Every deny case asserts the
 * exception rather than a fallback, because a principal-only fallback here would silently strip the
 * agent's narrowing.
 */
class ClaimDelegationChainExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRINCIPAL = "user-alice";

    private final IdentityProperties properties = new IdentityProperties();
    private final ClaimDelegationChainExtractor extractor =
            new ClaimDelegationChainExtractor(MAPPER, properties);

    private AbacContext.Subject subjectWith(Object claim) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (claim != null) {
            attributes.put(properties.getActorClaim(), claim);
        }
        return new AbacContext.Subject(PRINCIPAL, List.of("catalog-reader"), attributes);
    }

    private static Map<String, Object> act(String sub, Map<String, Object> nested) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("sub", sub);
        if (nested != null) {
            node.put("act", nested);
        }
        return node;
    }

    @Test // U5 — flattened shape, one actor
    void readsASingleActorFromTheFlattenedShape() {
        DelegationChain chain = extractor.extract(subjectWith(List.of("agent-a")));

        assertThat(chain.principal()).isEqualTo(PRINCIPAL);
        assertThat(chain.actor()).isEqualTo("agent-a");
        assertThat(chain.chain()).containsExactly("agent-a");
        assertThat(chain.isAgentCall()).isTrue();
        assertThat(chain.depth()).isEqualTo(1);
    }

    @Test // U5 — the same, in RFC 8693 nested form
    void readsASingleActorFromTheNestedShape() {
        DelegationChain chain = extractor.extract(subjectWith(act("agent-a", null)));

        assertThat(chain.actor()).isEqualTo("agent-a");
        assertThat(chain.chain()).containsExactly("agent-a");
    }

    @Test // U6 — order is the contract: nearest actor first, in both encodings
    void preservesOrderAcrossTwoHopsInBothShapes() {
        DelegationChain nested =
                extractor.extract(subjectWith(act("agent-a", act("agent-b", null))));
        DelegationChain flattened =
                extractor.extract(subjectWith(List.of("agent-a", "agent-b")));

        assertThat(nested.chain()).containsExactly("agent-a", "agent-b");
        assertThat(flattened.chain()).containsExactly("agent-a", "agent-b");
        // The immediate caller — the one the tool-gate narrows by — is the outermost actor.
        assertThat(nested.actor()).isEqualTo("agent-a");
        assertThat(flattened.actor()).isEqualTo("agent-a");
        assertThat(nested.chain()).isEqualTo(flattened.chain());
    }

    @Test // U5 — a chain of objects rather than bare strings
    void readsTheFlattenedShapeWithSubObjects() {
        DelegationChain chain =
                extractor.extract(subjectWith(List.of(act("agent-a", null), act("agent-b", null))));

        assertThat(chain.chain()).containsExactly("agent-a", "agent-b");
    }

    @Test // U7 — the ONE shape that is not a deny
    void treatsAnAbsentClaimAsAnOrdinaryHumanCall() {
        DelegationChain chain = extractor.extract(subjectWith(null));

        assertThat(chain.principal()).isEqualTo(PRINCIPAL);
        assertThat(chain.actor()).isNull();
        assertThat(chain.chain()).isEmpty();
        assertThat(chain.isAgentCall()).isFalse();
    }

    @Test // U8 — a scalar where an object or array belongs
    void deniesAClaimOfTheWrongType() {
        assertThatThrownBy(() -> extractor.extract(subjectWith("agent-a")))
                .isInstanceOf(DelegationChainException.class);
        assertThatThrownBy(() -> extractor.extract(subjectWith(42)))
                .isInstanceOf(DelegationChainException.class);
        assertThatThrownBy(() -> extractor.extract(subjectWith(Boolean.TRUE)))
                .isInstanceOf(DelegationChainException.class);
    }

    @Test // U8 — an actor entry with no id at all
    void deniesAnActorEntryWithoutAnId() {
        assertThatThrownBy(() -> extractor.extract(subjectWith(Map.of("role", "agent"))))
                .isInstanceOf(DelegationChainException.class);
    }

    @Test // U8 — present but naming nobody is malformed, NOT "human"
    void deniesAPresentButEmptyClaim() {
        assertThatThrownBy(() -> extractor.extract(subjectWith(List.of())))
                .isInstanceOf(DelegationChainException.class)
                .hasMessageContaining("names no actor");
    }

    @Test // U9a — depth above the configured maximum
    void deniesAChainDeeperThanTheLimit() {
        properties.setMaxChainDepth(4);
        List<String> fiveDeep = List.of("agent-a", "agent-b", "agent-c", "agent-d", "agent-e");

        assertThatThrownBy(() -> extractor.extract(subjectWith(fiveDeep)))
                .isInstanceOf(DelegationChainException.class)
                .hasMessageContaining("deeper than");
    }

    @Test // U9a — the nested shape is bounded during the walk, by OUR depth check
    void deniesANestedChainDeeperThanTheLimit() {
        properties.setMaxChainDepth(4);
        properties.setMaxClaimLength(1_000_000);

        Map<String, Object> claim = nested(6);

        assertThatThrownBy(() -> extractor.extract(subjectWith(claim)))
                .isInstanceOf(DelegationChainException.class)
                .hasMessageContaining("deeper than");
    }

    @Test // U9a — a pathologically deep claim denies WITHOUT throwing an Error
    void deniesAPathologicallyDeepClaimWithoutAStackOverflow() {
        properties.setMaxChainDepth(4);
        properties.setMaxClaimLength(Integer.MAX_VALUE);

        Map<String, Object> claim = nested(10_000);

        // assertThatThrownBy catches Throwable, so this also proves no StackOverflowError escapes —
        // the repo has been bitten once by an Error slipping past a catch(Exception) fail-closed
        // handler, and a deeply nested act claim is exactly that shape.
        assertThatThrownBy(() -> extractor.extract(subjectWith(claim)))
                .isInstanceOf(DelegationChainException.class)
                .isNotInstanceOf(Error.class);
    }

    /** {@code {"sub":"agent-0","act":{"sub":"agent-1",…}}}, {@code depth} levels deep. */
    private static Map<String, Object> nested(int depth) {
        Map<String, Object> node = act("agent-" + (depth - 1), null);
        for (int i = depth - 2; i >= 0; i--) {
            node = act("agent-" + i, node);
        }
        return node;
    }

    @Test // U9b — the size cap is a separate edge from the depth cap
    void deniesAnOversizedClaim() {
        properties.setMaxClaimLength(64);

        assertThatThrownBy(() -> extractor.extract(subjectWith(List.of("a".repeat(120)))))
                .isInstanceOf(DelegationChainException.class)
                .hasMessageContaining("size limit");
    }

    @Test // U10 — an actor claiming to act for itself is not a delegation
    void deniesAnActorEqualToThePrincipal() {
        assertThatThrownBy(() -> extractor.extract(subjectWith(List.of("agent-a", PRINCIPAL))))
                .isInstanceOf(DelegationChainException.class)
                .hasMessageContaining("cyclic");
    }

    @Test // U10 — a repeated actor pads the chain to look longer than it is
    void deniesARepeatedActor() {
        assertThatThrownBy(
                        () -> extractor.extract(subjectWith(List.of("agent-a", "agent-b", "agent-a"))))
                .isInstanceOf(DelegationChainException.class)
                .hasMessageContaining("cyclic");
    }

    @Test // U11 — a blank id
    void deniesABlankActorId() {
        assertThatThrownBy(() -> extractor.extract(subjectWith(List.of("   "))))
                .isInstanceOf(DelegationChainException.class);
    }

    @Test // U11 — a non-string id
    void deniesANonStringActorId() {
        assertThatThrownBy(() -> extractor.extract(subjectWith(List.of(7))))
                .isInstanceOf(DelegationChainException.class)
                .hasMessageContaining("non-string");
    }

    @Test // U11 — ids that would be unsafe to serialize into a policy input or a log line
    void deniesActorIdsOutsideTheLegalCharset() {
        assertThatThrownBy(() -> extractor.extract(subjectWith(List.of("agent a"))))
                .isInstanceOf(DelegationChainException.class)
                .hasMessageContaining("illegal");
        assertThatThrownBy(() -> extractor.extract(subjectWith(List.of("agent\nb"))))
                .isInstanceOf(DelegationChainException.class);
        assertThatThrownBy(() -> extractor.extract(subjectWith(List.of("../../etc/passwd"))))
                .isInstanceOf(DelegationChainException.class);
        assertThatThrownBy(() -> extractor.extract(subjectWith(List.of("a".repeat(129)))))
                .isInstanceOf(DelegationChainException.class);
    }

    @Test // U11 — legitimate IdP id shapes must still pass
    void acceptsRealisticActorIds() {
        assertThat(extractor.extract(subjectWith(List.of("svc.agent-1_x@example:demo"))).actor())
                .isEqualTo("svc.agent-1_x@example:demo");
    }

    @Test // U12 — with no principal there is nothing honest to evaluate
    void deniesABlankPrincipal() {
        AbacContext.Subject blank = new AbacContext.Subject("   ", List.of(), Map.of());

        assertThatThrownBy(() -> extractor.extract(blank))
                .isInstanceOf(DelegationChainException.class)
                .hasMessageContaining("no principal");
        assertThatThrownBy(() -> extractor.extract(null))
                .isInstanceOf(DelegationChainException.class);
    }

    @Test // the claim name is configuration, not a constant
    void readsTheConfiguredClaimName() {
        properties.setActorClaim("delegation");
        AbacContext.Subject subject = new AbacContext.Subject(
                PRINCIPAL, List.of(), Map.of("delegation", List.of("agent-a")));

        assertThat(extractor.extract(subject).actor()).isEqualTo("agent-a");
    }
}
