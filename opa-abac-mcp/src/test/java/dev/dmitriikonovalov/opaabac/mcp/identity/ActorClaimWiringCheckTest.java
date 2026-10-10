package dev.dmitriikonovalov.opaabac.mcp.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The startup guard against a <strong>silent widening</strong>: if the actor claim is not one the starter
 * copies into the subject, the extractor reads "no actor claim" and every agent call evaluates as an
 * ordinary human one, with the agent narrowing quietly gone and nothing failing.
 */
class ActorClaimWiringCheckTest {

    private final IdentityProperties identity = new IdentityProperties();
    private List<String> copiedClaims = List.of();

    private ActorClaimWiringCheck check() {
        return new ActorClaimWiringCheck(identity, copiedClaims);
    }

    @Test
    void passesWhenTheClaimIsCopiedIntoTheSubject() {
        identity.setActorClaim("act_chain");
        copiedClaims = List.of("act_chain");

        assertThatCode(() -> check().afterSingletonsInstantiated()).doesNotThrowAnyException();
    }

    @Test // the module cannot see the starter: a null list is read as "nothing copied", never as a pass
    void aNullClaimListFailsLikeAnEmptyOne() {
        identity.setActorClaim("act_chain");
        copiedClaims = null;

        assertThatThrownBy(() -> check().afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("silent widening");
    }

    @Test // the drift this guard exists for
    void failsWhenTheStarterDoesNotCopyTheClaim() {
        identity.setActorClaim("act_chain");
        copiedClaims = List.of();

        assertThatThrownBy(() -> check().afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("act_chain")
                .hasMessageContaining("opa.abac.mcp.identity.actor-claim")
                .hasMessageContaining("opa.abac.subject.attribute-claims")
                .hasMessageContaining("silent widening");
    }

    @Test // a rename on one side only
    void failsWhenTheTwoSettingsDisagree() {
        identity.setActorClaim("delegation");
        copiedClaims = List.of("act_chain");

        assertThatThrownBy(() -> check().afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delegation");
    }

    @Test
    void failsWhenTheClaimNameIsBlank() {
        identity.setActorClaim("  ");
        copiedClaims = List.of("act_chain");

        assertThatThrownBy(() -> check().afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }
}
