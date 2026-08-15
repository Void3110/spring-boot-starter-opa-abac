package dev.dmitriikonovalov.example.catalog.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.opaabac.autoconfigure.OpaAbacProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The startup guard against a <strong>silent widening</strong>: if {@code act_chain} is not one of the
 * claims the starter copies into the subject, the policy's presence-test is permanently undefined and
 * the supervised-leg guard reads every agent call as an ordinary human one — nothing fails, logs, or
 * looks wrong. The freshness claims get the softer twin check: their absence is fail-closed but turns
 * the step-up challenge off silently.
 */
class StepUpClaimsWiringCheckTest {

    private final OpaAbacProperties starter = new OpaAbacProperties();

    private StepUpClaimsWiringCheck check() {
        return new StepUpClaimsWiringCheck(starter);
    }

    @Test
    void passesWhenAllThreeClaimsAreCopiedIntoTheSubject() {
        starter.getSubject().setAttributeClaims(List.of("acr", "auth_time", "act_chain"));

        assertThatCode(() -> check().afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test // the drift this guard exists for
    void failsWhenTheDelegationClaimIsTrimmed() {
        starter.getSubject().setAttributeClaims(List.of("acr", "auth_time"));

        StepUpClaimsWiringCheck check = check();
        assertThatThrownBy(check::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("act_chain")
                .hasMessageContaining("silent widening");
    }

    @Test // fail-closed, but feature-off should be a decision, not a typo
    void failsWhenAFreshnessClaimIsTrimmed() {
        starter.getSubject().setAttributeClaims(List.of("acr", "act_chain"));

        StepUpClaimsWiringCheck check = check();
        assertThatThrownBy(check::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth_time")
                .hasMessageContaining("plain 403");
    }

    @Test
    void failsOnAnEmptyList() {
        starter.getSubject().setAttributeClaims(List.of());

        StepUpClaimsWiringCheck check = check();
        assertThatThrownBy(check::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("act_chain");
    }
}
