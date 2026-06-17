package dev.dmitriikonovalov.example.usermgmt.security;

import dev.dmitriikonovalov.opaabac.security.web.Enrichable;
import java.util.List;

/**
 * Stamps the {@link Enrichable} affordance contract onto the generated {@code Team} DTO (via the OpenAPI
 * {@code x-implements} extension) — the <em>second</em> registry shape (the control plane), proving the
 * per-type sub-interface mechanism generalizes beyond catalog CRUD.
 *
 * <p><strong>Only fully-OPA-decided verbs are enumerated</strong> (affordance honesty, ADR 0016 §8): a
 * {@code true} must mean the caller can actually perform the action. The team set is therefore the
 * OPA-decided subset {@code [list-members, add-member, remove-member]}. The control-plane escalation verbs
 * {@code change-role}, {@code define-roles}, and {@code transfer-ownership} are <strong>deliberately
 * excluded</strong>: each is co-gated in Java by {@code MembershipService}'s escalation gates
 * (no-self-escalation, the subset rule, level-strict-{@code <}) and the owner-only-by-code fence, which OPA
 * cannot see — OPA alone would say {@code change-role:true} for any member whose role category permits it
 * while the Java gate still rejects the specific escalation, so enumerating them would over-promise and the
 * UI button would 4xx. Enrichment stays a pure one-{@code bulk}-call read.
 */
public interface TeamEnrichable extends Enrichable {

    @Override
    default String abacResourceType() {
        return "team";
    }

    @Override
    default List<String> abacActions() {
        return List.of("list-members", "add-member", "remove-member");
    }
}
