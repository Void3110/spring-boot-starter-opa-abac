package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.Map;
import java.util.Set;

/**
 * Which allowed reads are <em>privileged</em> enough to audit — the adopter's vocabulary, not the
 * library's (ADR 0030 §8, Amendment 7).
 *
 * <h2>Why this is configuration and not a constant</h2>
 * The event this policy gates ({@code PRIVILEGED_READ}) answers a question only the
 * <em>adopter's</em> domain can pose: "an oversight role read production-tier content." The words
 * {@code supervised} and {@code production} are this repo's example vocabulary; a published starter
 * that hardcoded them would fire an event no adopter asked for when their nouns happen to match, and
 * never fire at all when they do not — with no way to configure or suppress either outcome.
 *
 * <p>What stays fixed is the <strong>computability pin</strong> ADR 0030 §8 exists for: the trigger
 * is evaluated where the decision is made, from the decision's own inputs — the granted allow, the
 * resolved role, and the enriched governing-root attributes at that instant. Elevation is implied by
 * the allow and is never re-derived; only the <em>names</em> are configurable. Moving the trigger
 * app-side would have forced exactly the re-derivation that amendment forbids.
 *
 * <p><strong>Absent means silent.</strong> No policy configured ⇒ no privileged-read event. The
 * library's other audit event, {@code STEP_UP_CHALLENGED}, is vocabulary-free (it reports a challenge
 * the library itself minted) and is unaffected by this policy.
 *
 * @param provenance    the {@code role_definition.attributes.provenance} stamp marking the privileged
 *                      access path (this repo's example: {@code supervised})
 * @param rootAttribute the governing root's attribute naming the tier (example: {@code env})
 * @param rootValues    the tier values that make a read privileged (example: {@code production});
 *                      matched against a scalar attribute <em>or</em> any element of an array one —
 *                      the cardinality twin the policy's own {@code root_env_values} normalizes
 */
public record PrivilegedReadAuditPolicy(String provenance, String rootAttribute, Set<String> rootValues) {

    public PrivilegedReadAuditPolicy {
        // One message for every incomplete shape, nulls included: a half-configured policy is a typo,
        // and the caller needs to be told WHICH knobs exist rather than handed an NPE.
        rootValues = rootValues == null ? Set.of() : Set.copyOf(rootValues);
        if (provenance == null || provenance.isBlank()
                || rootAttribute == null || rootAttribute.isBlank()
                || rootValues.isEmpty()) {
            throw new IllegalArgumentException(
                    "a privileged-read audit policy needs a provenance, a root attribute and at least "
                            + "one root value (got provenance=" + provenance + ", rootAttribute="
                            + rootAttribute + ", rootValues=" + rootValues + "); leave the whole "
                            + "policy unset to disable the event");
        }
    }

    /**
     * Whether this read is a privileged one worth auditing.
     *
     * <p>Absent root attributes mean <em>no</em>: nothing proved the read was privileged, which is also
     * the state the policy treats as an unproven (closed) tier.
     *
     * @param roleDefinition the resolved role behind the allow, or {@code null}
     * @param rootAttributes the governing root's enriched attributes, or {@code null}
     * @return {@code true} when the role carries the configured provenance and the root's tier
     *         attribute contains one of the configured values
     */
    public boolean matches(RoleDefinition roleDefinition, Map<String, Object> rootAttributes) {
        if (roleDefinition == null || rootAttributes == null) {
            return false;
        }
        if (!provenance.equals(roleDefinition.attributes().get("provenance"))) {
            return false;
        }
        Object tier = rootAttributes.get(rootAttribute);
        if (tier instanceof Iterable<?> values) {
            for (Object value : values) {
                if (value != null && rootValues.contains(value.toString())) {
                    return true;
                }
            }
            return false;
        }
        return tier != null && rootValues.contains(tier.toString());
    }
}
