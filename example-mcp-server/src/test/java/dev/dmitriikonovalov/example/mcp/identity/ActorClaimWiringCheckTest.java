package dev.dmitriikonovalov.example.mcp.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.opaabac.autoconfigure.OpaAbacProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The startup guard against a <strong>silent widening</strong>: if the actor claim is not one the starter
 * copies into the subject, the extractor reads "no actor claim" and every agent call evaluates as an
 * ordinary human one, with the agent narrowing quietly gone and nothing failing.
 */
class ActorClaimWiringCheckTest {

    private final IdentityProperties identity = new IdentityProperties();
    private final OpaAbacProperties starter = new OpaAbacProperties();

    private ActorClaimWiringCheck check() {
        return new ActorClaimWiringCheck(identity, starter);
    }

    @Test
    void passesWhenTheClaimIsCopiedIntoTheSubject() {
        identity.setActorClaim("act_chain");
        starter.getSubject().setAttributeClaims(List.of("act_chain"));

        assertThatCode(() -> check().afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test // the drift this guard exists for
    void failsWhenTheStarterDoesNotCopyTheClaim() {
        identity.setActorClaim("act_chain");
        starter.getSubject().setAttributeClaims(List.of());

        assertThatThrownBy(() -> check().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("act_chain")
                .hasMessageContaining("silent widening");
    }

    @Test // a rename on one side only
    void failsWhenTheTwoSettingsDisagree() {
        identity.setActorClaim("delegation");
        starter.getSubject().setAttributeClaims(List.of("act_chain"));

        assertThatThrownBy(() -> check().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delegation");
    }

    @Test
    void failsWhenTheClaimNameIsBlank() {
        identity.setActorClaim("  ");
        starter.getSubject().setAttributeClaims(List.of("act_chain"));

        assertThatThrownBy(() -> check().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }
}
