package dev.dmitriikonovalov.opaabac.mcp.authz;

import dev.dmitriikonovalov.opaabac.core.DecisionIndeterminateException;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.Optional;

/**
 * The principal's <strong>ceiling</strong> for the tool-gate: what the caller may do to <em>some</em>
 * resource of a type — the human half of <em>principal ceiling ∩ agent capability</em> (ADR 0036 §6).
 *
 * <p>The tool-gate deliberately resolves no target: it asks "may this caller invoke this tool at all?"
 * before any row is known, so it has no resource id to resolve a role against. This SPI answers the
 * type-level question instead. The target service still decides every particular resource itself,
 * with the caller's own bearer, under its own unchanged policy.
 *
 * <h2>The contract</h2>
 * <ul>
 *   <li><strong>Union of grants.</strong> A principal who may write resource A and only read resource B
 *       has type-level authority "read and write <em>some</em> resource of this type": grants are unioned
 *       across every resource the principal governs, and a <strong>denial is kept only when it applies to
 *       all of them</strong> — a denial on one resource says nothing about another.</li>
 *   <li><strong>Over-approximation is allowed; under-approximation is forbidden.</strong> The gate is not
 *       the authority on resources — the target service is — so a ceiling slightly wider than any one
 *       resource's role can only let through a call the target then decides properly. A ceiling that is
 *       too narrow removes access the caller has over the target's own API, and hides the agent narrowing
 *       behind a principal denial.</li>
 *   <li><strong>{@link Optional#empty()} is an authoritative no-role</strong> — the principal governs
 *       nothing of this type. The policy default-denies. It is a real answer, never "unknown".</li>
 *   <li><strong>An outage throws</strong> a {@link DecisionIndeterminateException} subtype (the core
 *       library's {@code RoleResolutionException}, or the implementation's own family member): the
 *       gate then denies with {@code tool-gate-ceiling-unavailable}. An unknown ceiling is never read as
 *       "no ceiling", and certainly never as "any ceiling". Implementations must never return
 *       {@code null}.</li>
 *   <li><strong>No default implementation ships.</strong> A server without one fails startup — the gate
 *       has no honest answer to fall back on.</li>
 * </ul>
 *
 * <h2>Implementer's note: roles recorded on a governing root</h2>
 * When authority is recorded on a <em>governing root</em> (a catalog) and the role it resolves to carries
 * permissions for the whole hierarchy below it, the asked type (a product) may have no directly governed
 * resources at all. Enumerating only the asked type then under-approximates — exactly the forbidden
 * direction. Include the governing root's type in the lookup when the asked type is one of its
 * descendants (the example's {@code TypeLevelPrincipalCeilingSupplier} does this with a configured list
 * of grant-scope types).
 *
 * <h2>Why this is not {@code RoleDefinitionSupplier}</h2>
 * The core supplier answers a <em>per-resource</em> question, and its {@code resourceId} is part of that
 * contract. Calling it with a {@code null} id would silently reinterpret every adopter's existing supplier
 * — one that resolves exactly the role on exactly one resource would be read as a type-level union it
 * never computed. A separate SPI makes the type-level contract something an implementation opts into.
 *
 * <p>Within one web request the starter decorates the bean with {@link MemoizingPrincipalCeilingSupplier}
 * (ADR 0023): the roster and the call path see one answer per {@code (subject, resourceType)}.
 */
@FunctionalInterface
public interface PrincipalCeilingSupplier {

    /**
     * The principal's type-level ceiling.
     *
     * @param subject      the principal (the token {@code sub}) — never the agent
     * @param resourceType the resource type the tool reads (its declared target type)
     * @return the unioned role definition, or {@link Optional#empty()} for an authoritative no-role. Never
     *     {@code null}.
     * @throws DecisionIndeterminateException when the ceiling source was unavailable — the caller denies
     *     and never falls back
     */
    Optional<RoleDefinition> lookup(String subject, String resourceType);
}
