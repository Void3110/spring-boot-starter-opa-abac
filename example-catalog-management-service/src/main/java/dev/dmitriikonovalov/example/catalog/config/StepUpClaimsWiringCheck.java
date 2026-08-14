package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.opaabac.autoconfigure.OpaAbacProperties;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fails startup unless the step-up claims this service's gates key on are ones the starter actually
 * copies into the subject.
 *
 * <h2>Why this is a security control and not a convenience</h2>
 * The agent discriminator is hardcoded in two enforcement points — {@link CatalogListAuthorizer}'s
 * supervised-leg guard and the per-type policies' {@code is_agent_call} presence-test — but reaches
 * {@code AbacContext.Subject#attributes()} only for claim names listed in
 * {@code opa.abac.subject.attribute-claims}. If that list is trimmed or the name drifts, the claim
 * simply is not there: the policy's presence-test is permanently undefined, the supervised leg reads
 * "ordinary human call", and every agent-marked call takes the wider human branch. That is a
 * <em>silent widening</em> — nothing fails, logs, or looks wrong — arriving through the back door of
 * a config typo. The same guard exists in the MCP server ({@code ActorClaimWiringCheck}) for the
 * tool-gate's claim; this is its target-gate twin.
 *
 * <p>{@code acr} and {@code auth_time} are checked too, for the opposite failure shape: trimming
 * them is fail-closed (an absent claim leaves {@code elevated} undefined and the production deny
 * holds), but it silently turns every supervised production read into an unanswerable plain 403 —
 * the challenge this slice exists to emit can never fire. Feature-off should be a decision, not a
 * typo.
 *
 * <p>Gated on the starter like {@code SecurityConfig}'s ABAC beans: the unguarded baseline boot
 * (ADR 0021 §2, {@code opa.abac.enabled=false}) has no gates to guard and no
 * {@code OpaAbacProperties} bean to read.
 */
@Component
@ConditionalOnProperty(prefix = "opa.abac", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StepUpClaimsWiringCheck implements InitializingBean {

    /** The freshness claims the {@code elevated} predicate reads (ADR 0030 §5). */
    static final List<String> FRESHNESS_CLAIMS = List.of("acr", "auth_time");

    private final OpaAbacProperties starterProperties;

    public StepUpClaimsWiringCheck(OpaAbacProperties starterProperties) {
        this.starterProperties = starterProperties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> copiedClaims = starterProperties.getSubject().getAttributeClaims();

        if (!copiedClaims.contains(CatalogListAuthorizer.AGENT_DELEGATION_CLAIM)) {
            throw new IllegalStateException(
                    "the agent delegation claim '" + CatalogListAuthorizer.AGENT_DELEGATION_CLAIM
                            + "' is not listed in opa.abac.subject.attribute-claims=" + copiedClaims
                            + ". The claim would never reach the subject, so every agent call would be "
                            + "read as an ordinary human call — a silent widening. Add it back.");
        }
        for (String claim : FRESHNESS_CLAIMS) {
            if (!copiedClaims.contains(claim)) {
                throw new IllegalStateException(
                        "the step-up freshness claim '" + claim + "' is not listed in "
                                + "opa.abac.subject.attribute-claims=" + copiedClaims
                                + ". Elevation would be permanently undefined, so every supervised "
                                + "production read would answer an unanswerable plain 403 instead of "
                                + "the step-up challenge. Add it back, or remove this check together "
                                + "with the step-up feature.");
            }
        }
    }
}
