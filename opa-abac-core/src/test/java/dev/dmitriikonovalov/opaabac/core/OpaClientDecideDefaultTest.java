package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * U12 — the additivity proof at the <em>interface</em> level: an {@link OpaClient} written before
 * {@code decide} existed still compiles and behaves unchanged.
 *
 * <p>{@link LegacyOpaClient} below implements only the three methods that existed before ADR 0030 §6.
 * That it compiles at all is half the assertion; the other half is that its inherited {@code decide}
 * answers the same boolean its {@code allow} does, with no reason.
 */
class OpaClientDecideDefaultTest {

    private static AbacContext context() {
        return new AbacContext(
                new AbacContext.Subject("u", List.of(), Map.of()),
                "catalog:view",
                new AbacContext.Resource("catalog", "c-1", Map.of()),
                Map.of());
    }

    @Test
    void defaultDecide_mirrorsAllowWithNoReason() {
        assertThat(new LegacyOpaClient(true).decide(context())).isEqualTo(new OpaDecision(true, null));
        assertThat(new LegacyOpaClient(false).decide(context())).isEqualTo(new OpaDecision(false, null));
    }

    @Test
    void defaultDecide_callsAllowExactlyOnce() {
        LegacyOpaClient client = new LegacyOpaClient(true);

        client.decide(context());

        assertThat(client.allowCalls).isEqualTo(1);
    }

    /** An implementation frozen at the pre-{@code decide} interface — the whole point of the case. */
    private static final class LegacyOpaClient implements OpaClient {

        private final boolean verdict;
        private int allowCalls;

        private LegacyOpaClient(boolean verdict) {
            this.verdict = verdict;
        }

        @Override
        public boolean allow(AbacContext context) {
            allowCalls++;
            return verdict;
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.denyAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return contexts.stream().map(c -> verdict).toList();
        }
    }
}
