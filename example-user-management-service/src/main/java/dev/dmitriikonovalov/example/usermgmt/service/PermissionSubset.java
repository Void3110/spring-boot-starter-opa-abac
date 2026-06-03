package dev.dmitriikonovalov.example.usermgmt.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The no-self-escalation <b>subset rule</b> (the Kubernetes anti-escalation rule, {@code 00-DESIGN.md}
 * hard rule 2): you cannot grant — by assigning a role (ticket 4) or defining a custom one (ticket 5)
 * — more than you hold. A candidate permission set is allowed only if, for every resource type, its
 * verbs are a subset of the actor's own verbs for that type.
 *
 * <p>Shared by {@code MembershipService} (assign) and {@code RoleDefinitionService} (define) — one
 * implementation of the rule, not two. The wildcard resource type {@code "*"} in the actor's
 * permissions (system roles are target-type-agnostic) grants every type, so a wildcard owner/admin
 * passes any same-or-narrower candidate.
 */
public final class PermissionSubset {

    private static final String WILDCARD = "*";

    private PermissionSubset() {
    }

    /**
     * @return true iff {@code candidate} grants nothing beyond {@code actor} — for every resource type
     *     in the candidate, every verb is also granted to the actor (directly or via the {@code "*"}
     *     wildcard).
     */
    public static boolean isSubset(
            Map<String, List<String>> candidate, Map<String, List<String>> actor) {
        if (candidate == null || candidate.isEmpty()) {
            return true; // grants nothing → trivially a subset
        }
        if (actor == null) {
            return false;
        }
        Set<String> wildcardVerbs = Set.copyOf(actor.getOrDefault(WILDCARD, List.of()));
        for (var entry : candidate.entrySet()) {
            Set<String> actorVerbs = Set.copyOf(actor.getOrDefault(entry.getKey(), List.of()));
            for (String verb : entry.getValue()) {
                if (!actorVerbs.contains(verb) && !wildcardVerbs.contains(verb)) {
                    return false;
                }
            }
        }
        return true;
    }
}
