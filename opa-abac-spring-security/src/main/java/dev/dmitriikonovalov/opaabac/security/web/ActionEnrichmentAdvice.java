package dev.dmitriikonovalov.opaabac.security.web;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.AncestorChainSupplier;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.ResolveTarget;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Attaches an {@code _actions} <em>affordance map</em> — for each returned {@link Enrichable} resource,
 * which actions the caller may perform on it — after the handler returns, by one batch OPA call per
 * resource type against the resource's <em>resolved attributes</em> (the request-scoped
 * {@link AbacResourceCache}). This is <strong>affordance, not enforcement</strong>: it never blocks a
 * request; the gate (ADR 0006) still decides independently.
 *
 * <p>Recognized returns: a single {@link Enrichable}, an {@code Iterable<Enrichable>}, or a paged
 * envelope whose {@code getItems()} returns a {@code List} of {@link Enrichable} (the ADR-0012
 * {@code <Resource>Page} shape). Per resource-type group the flow is <strong>two-pass</strong>
 * (Slice 7.3, ADR 0024 §5):
 * <ol>
 *   <li><b>Pass 1 — prepare.</b> For each row: verbs present? cached snapshot present? ancestor
 *       chain resolved (through the request-memoized supplier) → the governing root (mirroring the
 *       5.97 / hierarchical gate). Rows that cannot be prepared are dropped (their {@code _actions}
 *       stays unset). The computable rows' <em>distinct</em> governing roots are collected.</li>
 *   <li><b>One batch role resolution.</b> A single
 *       {@link RoleDefinitionSupplier#lookupAll(String, Set)} resolves every distinct root at once —
 *       one wire exchange even when every row is its own root (the multi-root page a
 *       duplicate-target memo cannot help). Batching is unconditional code (call-coalescing with
 *       identical point-in-time semantics — not caching), so it is not governed by the memo flag.</li>
 *   <li><b>Pass 2 — decide + refold.</b> Per-row contexts are built from the returned map (an
 *       {@code empty} entry → {@code role=null}, exactly the old per-row {@code orElse(null)}), the
 *       flat {@code rows × verbs} context list (row-major: row <em>i</em>, verb <em>j</em> → index
 *       {@code i·V+j}) goes to <strong>one</strong> {@link OpaClient#allowAll(List)} per type, and
 *       the positional verdicts are re-folded into per-row {@code Map<verb,Boolean>}.</li>
 * </ol>
 *
 * <h2>The degrade contract: omit, never fabricate (ADR 0016 §7)</h2>
 * A row's {@code _actions} is set <strong>only</strong> when it can be computed completely and
 * honestly. It is <strong>omitted</strong> (left unset) on every failure class — per-row where the
 * failure is the row's, for the whole group where it is the batch's:
 * <ul>
 *   <li>no authenticated subject (all rows);</li>
 *   <li>a cache miss (that row) or an ancestor-resolution failure (that row);</li>
 *   <li>the batch role resolution throwing {@link RoleResolutionException} — a <em>whole-batch</em>
 *       outage by contract (ADR 0024 §2): every root's answer is unknown, so the <strong>whole
 *       group</strong> is omitted while the response body stays intact (affordance never blocks).
 *       Pre-7.3 a role outage omitted the row that hit it; the batch form makes the same outage
 *       omit the page's group — a fully-degraded page over a mixed-snapshot one (ADR 0023's
 *       posture);</li>
 *   <li>{@code allowAll} throwing, or returning a list whose length does not match the batch;</li>
 *   <li><strong>an all-{@code false} verdict block</strong> for the row — the production
 *       {@link OpaClient#allowAll(List)} fails closed to all-{@code false} on a transport error, which
 *       is indistinguishable from a genuine fully-denied resource by the returned booleans alone; the
 *       advice therefore treats an all-{@code false} block as <em>could-not-compute</em> and omits,
 *       rather than risk emitting a fabricated all-{@code false} map (the inverting-client footgun
 *       ADR 0016 §7 forbids). A caller who reached enrichment already passed a gated read, so a real
 *       row almost always has at least one {@code true} (typically {@code view}).</li>
 * </ul>
 * A present map is therefore always <em>complete</em> (every {@link Enrichable#abacActions()} verb
 * keyed with a real verdict) and has at least one {@code true}; an absent map means enrichment could
 * not be computed and the client falls back to its own default affordance.
 */
@RestControllerAdvice
public class ActionEnrichmentAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(ActionEnrichmentAdvice.class);

    private final OpaClient opaClient;
    private final AbacResourceCache cache;
    private final RoleDefinitionSupplier roleDefinitionSupplier;
    private final AncestorChainSupplier ancestorChainSupplier;

    /**
     * @param opaClient              the batch primitive (reused verbatim — no enrichment-specific method)
     * @param cache                  the request-scoped resolved-attribute snapshot store
     * @param roleDefinitionSupplier resolves the governing-root role (the same SPI the gate uses)
     * @param ancestorChainSupplier  resolves a resource's ancestor chain, or {@code null} for a flat
     *                               deployment (then every resource is its own governing root)
     */
    public ActionEnrichmentAdvice(
            OpaClient opaClient,
            AbacResourceCache cache,
            RoleDefinitionSupplier roleDefinitionSupplier,
            AncestorChainSupplier ancestorChainSupplier) {
        this.opaClient = opaClient;
        this.cache = cache;
        this.roleDefinitionSupplier = roleDefinitionSupplier;
        this.ancestorChainSupplier = ancestorChainSupplier;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Cheap, type-only gate. The body itself is inspected in beforeBodyWrite (a paged envelope's
        // element type is not visible from the return type alone). Returning true here is safe: a body
        // that yields no Enrichable targets is returned unchanged, with no OPA call.
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body == null) {
            return body;
        }
        List<Enrichable> targets = collectEnrichable(body);
        if (targets.isEmpty()) {
            return body;
        }
        AbacContext.Subject subject = currentSubject();
        if (subject == null) {
            // No authenticated ABAC subject → cannot compute any affordance; omit for all (degrade).
            return body;
        }
        // One bulk call per resource type. Today's responses are homogeneous, but a defensive
        // group-by-type keeps the contract (one bulk per type) and never mixes a batch.
        Map<String, List<Enrichable>> byType = new LinkedHashMap<>();
        for (Enrichable e : targets) {
            byType.computeIfAbsent(e.abacResourceType(), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<Enrichable>> group : byType.entrySet()) {
            enrichGroup(subject, group.getValue());
        }
        return body;
    }

    /**
     * Enrich one resource-type group: pass 1 prepares each row (snapshot + ancestors + governing
     * root — a row that cannot be prepared is dropped, its {@code _actions} stays unset), then
     * <strong>one</strong> {@link RoleDefinitionSupplier#lookupAll} resolves the distinct governing
     * roots (a throw = whole-batch outage = the whole group omitted), then pass 2 builds the
     * contexts from the returned map and issues the single {@code allowAll}.
     */
    private void enrichGroup(AbacContext.Subject subject, List<Enrichable> group) {
        // Pass 1 — prepare the computable rows and collect their DISTINCT governing roots.
        List<PreparedRow> prepared = new ArrayList<>(group.size());
        Set<ResolveTarget> roots = new LinkedHashSet<>();
        int verbCount = -1;
        for (Enrichable dto : group) {
            List<String> verbs = dto.abacActions();
            if (verbs == null || verbs.isEmpty()) {
                continue; // nothing to ask → omit this row
            }
            RowInputs inputs = prepareRow(dto);
            if (inputs == null) {
                continue; // cache miss / ancestor failure → omit this row (degrade)
            }
            if (verbCount == -1) {
                verbCount = verbs.size();
            } else if (verbCount != verbs.size()) {
                // Mixed verb-set sizes within one type would corrupt the i·V+j refold. Verb sets are
                // intrinsic to the type (the sub-interface), so this is not expected; if it ever
                // happens, omit the odd row rather than mis-fold.
                continue;
            }
            ParentRef root = inputs.ancestors().isEmpty()
                    ? new ParentRef(dto.abacResourceType(), dto.getId().toString())
                    : inputs.ancestors().get(0);
            ResolveTarget target = new ResolveTarget(root.type(), root.id());
            roots.add(target);
            prepared.add(new PreparedRow(dto, verbs, inputs, target));
        }
        if (prepared.isEmpty()) {
            return;
        }

        // One batch role resolution for the page's distinct roots (ADR 0024 §5) — one wire exchange
        // even when every row is its own root. Unconditional: coalescing, not caching.
        Map<ResolveTarget, Optional<RoleDefinition>> roles;
        try {
            roles = roleDefinitionSupplier.lookupAll(subject.id(), roots);
        } catch (RoleResolutionException _) {
            // Whole-batch outage (B2's tri-state, batched): every root's answer is unknown → omit the
            // whole group, response body intact (never fall back, never widen, never block).
            log.warn("Action enrichment omitted for the group: role resolution outage (batch of {})",
                    roots.size());
            return;
        }

        // Pass 2 — contexts from the returned map (empty entry → role=null, the old orElse(null)).
        List<Enrichable> computable = new ArrayList<>(prepared.size());
        List<AbacContext> contexts = new ArrayList<>();
        for (PreparedRow row : prepared) {
            Optional<RoleDefinition> entry = roles.get(row.root());
            if (entry == null) {
                // Unreachable through the library (strict completeness is enforced by the contract
                // and re-checked by the memo decorator) — but a raw custom supplier could misbehave;
                // omit the row rather than enrich against a fabricated no-role.
                log.warn("Action enrichment omitted for {}:{}: batch result missing its root entry",
                        row.dto().abacResourceType(), row.dto().getId());
                continue;
            }
            String type = row.dto().abacResourceType();
            for (String verb : row.verbs()) {
                contexts.add(new AbacContext(
                        subject,
                        type + ":" + verb,
                        new AbacContext.Resource(type, row.dto().getId().toString(),
                                row.inputs().attributes(), row.inputs().ancestors()),
                        entry.orElse(null),
                        Map.of()));
            }
            computable.add(row.dto());
        }
        if (computable.isEmpty()) {
            return;
        }

        List<Boolean> verdicts;
        try {
            verdicts = opaClient.allowAll(contexts);
        } catch (RuntimeException ex) {
            // A custom OpaClient may throw; the production HttpOpaClient does not. Either way → omit all.
            log.warn("Action enrichment omitted: allowAll failed ({})", ex.getClass().getSimpleName());
            return;
        }
        if (verdicts == null || verdicts.size() != contexts.size()) {
            // Short/mismatched list → could-not-compute → omit all (never a partial or fabricated map).
            log.warn("Action enrichment omitted: allowAll returned {} verdicts for {} contexts",
                    verdicts == null ? "null" : verdicts.size(), contexts.size());
            return;
        }

        // Re-fold positional verdicts into per-row maps (row i, verb j → index i·V+j).
        int index = 0;
        for (Enrichable dto : computable) {
            List<String> verbs = dto.abacActions();
            Map<String, Boolean> actions = new LinkedHashMap<>();
            boolean anyTrue = false;
            for (String verb : verbs) {
                boolean allowed = Boolean.TRUE.equals(verdicts.get(index++));
                actions.put(verb, allowed);
                anyTrue = anyTrue || allowed;
            }
            // An all-false block is indistinguishable from a transport-error degrade (allowAll pads to
            // all-false on failure). Omit rather than risk a fabricated all-false map (ADR 0016 §7).
            if (anyTrue) {
                dto.setActions(actions);
            }
        }
    }

    /**
     * Resolve a row's pass-1 enrichment inputs from the cache: the resolved snapshot attributes and
     * the ancestor chain (through the request-memoized supplier — one real resolution per
     * {@code (type,id)} per request even though the query path asked first). Returns {@code null} on
     * a row-level failure (cache miss, ancestor failure) → the caller omits that row. The
     * governing-root <em>role</em> is deliberately NOT resolved here — pass 1 only derives the root;
     * the roles come back in one batch ({@code lookupAll}) for the whole group.
     */
    private RowInputs prepareRow(Enrichable dto) {
        String type = dto.abacResourceType();
        String id = dto.getId().toString();
        Optional<AbacResource> resolved = cache.get(type, id, AbacResource.class);
        if (resolved.isEmpty()) {
            return null; // cache miss → omit (degrade visibly, never re-resolve in the advice)
        }
        Map<String, Object> attributes = resolved.get().abacAttributes();

        List<ParentRef> ancestors;
        if (ancestorChainSupplier == null) {
            ancestors = List.of();
        } else {
            try {
                List<ParentRef> chain = ancestorChainSupplier.ancestorsOf(type, id);
                ancestors = chain == null ? List.of() : chain;
            } catch (RuntimeException ex) {
                // For enrichment honesty the decomposition omits on an ancestor failure rather than
                // silently degrading to direct-only (which could over-promise an inherited grant).
                log.warn("Action enrichment omitted for {}:{}: ancestor resolution failed ({})",
                        type, id, ex.getClass().getSimpleName());
                return null;
            }
        }
        return new RowInputs(attributes, ancestors);
    }

    private static AbacContext.Subject currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            return abac.getSubject();
        }
        return null; // no ABAC subject → cannot compute affordance (degrade)
    }

    /** A row's pass-1 inputs (resolved snapshot attributes + ancestors) — the role arrives in pass 2. */
    private record RowInputs(Map<String, Object> attributes, List<ParentRef> ancestors) {}

    /** A computable row awaiting its pass-2 role: the DTO, its verbs, its inputs, its governing root. */
    private record PreparedRow(Enrichable dto, List<String> verbs, RowInputs inputs, ResolveTarget root) {}

    // ---- enrichable collection ------------------------------------------------------------------

    /** Collect the enrichable DTOs from a single resource, an Iterable, or a paged envelope. */
    static List<Enrichable> collectEnrichable(Object body) {
        if (body instanceof Enrichable single) {
            return List.of(single);
        }
        if (body instanceof Iterable<?> iterable) {
            return enrichableElements(iterable);
        }
        List<?> items = pageItems(body);
        if (items != null) {
            return enrichableElements(items);
        }
        return List.of();
    }

    private static List<Enrichable> enrichableElements(Iterable<?> iterable) {
        List<Enrichable> out = new ArrayList<>();
        for (Object element : iterable) {
            if (element instanceof Enrichable e) {
                out.add(e);
            } else {
                // A heterogeneous collection with a non-Enrichable element is not an enrichable list.
                return List.of();
            }
        }
        return out;
    }

    /**
     * If {@code body} is a paged envelope (an ADR-0012 {@code <Resource>Page}), return its {@code items}
     * list; otherwise {@code null}. Detected structurally via a no-arg {@code getItems()} returning a
     * {@code List} — never a compile-time dependency on the example DTOs.
     */
    private static List<?> pageItems(Object body) {
        try {
            Method getItems = body.getClass().getMethod("getItems");
            if (List.class.isAssignableFrom(getItems.getReturnType())) {
                Object items = getItems.invoke(body);
                if (items instanceof List<?> list) {
                    return list;
                }
            }
        } catch (ReflectiveOperationException _) {
            // not a paged envelope
        }
        return null;
    }
}
