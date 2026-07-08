package dev.dmitriikonovalov.opaabac.security.web;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.AncestorChainSupplier;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * {@code <Resource>Page} shape). For each distinct resource the advice resolves its cached snapshot,
 * its governing-root role (mirroring the 5.97 / hierarchical gate), and builds the flat
 * {@code rows × verbs} context list (row-major: row <em>i</em>, verb <em>j</em> → index {@code i·V+j}),
 * issues <strong>one</strong> {@link OpaClient#allowAll(List)} per type, and re-folds the positional
 * verdicts into a per-row {@code Map<verb,Boolean>}.
 *
 * <h2>The degrade contract: omit, never fabricate (ADR 0016 §7)</h2>
 * A row's {@code _actions} is set <strong>only</strong> when it can be computed completely and
 * honestly. It is <strong>omitted</strong> (left unset) for that row on every failure class:
 * <ul>
 *   <li>no authenticated subject;</li>
 *   <li>a cache miss (no resolved snapshot for the row);</li>
 *   <li>an ancestor-resolution failure or a {@link RoleResolutionException} (role-source outage);</li>
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
     * Enrich one resource-type group with a single {@code allowAll}. Each row contributes its own
     * resolved snapshot + governing-root role; a row that cannot be prepared is dropped from the batch
     * (its {@code _actions} stays unset) so the batch carries only computable rows.
     */
    private void enrichGroup(AbacContext.Subject subject, List<Enrichable> group) {
        List<Enrichable> computable = new ArrayList<>(group.size());
        List<AbacContext> contexts = new ArrayList<>();
        int verbCount = -1;
        for (Enrichable dto : group) {
            List<String> verbs = dto.abacActions();
            if (verbs == null || verbs.isEmpty()) {
                continue; // nothing to ask → omit this row
            }
            RowContext row = prepareRow(subject, dto);
            if (row == null) {
                continue; // cache miss / ancestor / role failure → omit this row (degrade)
            }
            if (verbCount == -1) {
                verbCount = verbs.size();
            } else if (verbCount != verbs.size()) {
                // Mixed verb-set sizes within one type would corrupt the i·V+j refold. Verb sets are
                // intrinsic to the type (the sub-interface), so this is not expected; if it ever
                // happens, omit the odd row rather than mis-fold.
                continue;
            }
            String type = dto.abacResourceType();
            for (String verb : verbs) {
                contexts.add(new AbacContext(
                        subject,
                        type + ":" + verb,
                        new AbacContext.Resource(type, dto.getId().toString(), row.attributes(), row.ancestors()),
                        row.role(),
                        Map.of()));
            }
            computable.add(dto);
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
     * Resolve a row's enrichment inputs from the cache, mirroring the gate's governing-root role rule.
     * Returns {@code null} on any failure (cache miss, ancestor failure, role outage) → the caller omits.
     */
    private RowContext prepareRow(AbacContext.Subject subject, Enrichable dto) {
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

        ParentRef governingRoot = ancestors.isEmpty() ? new ParentRef(type, id) : ancestors.get(0);
        RoleDefinition role;
        try {
            role = roleDefinitionSupplier.lookup(subject.id(), governingRoot.type(), governingRoot.id())
                    .orElse(null);
        } catch (RoleResolutionException ex) {
            // Role-source outage (B2 tri-state) → unknown → omit (never fall back, never widen).
            log.warn("Action enrichment omitted for {}:{}: role resolution outage", type, id);
            return null;
        }
        return new RowContext(attributes, ancestors, role);
    }

    private static AbacContext.Subject currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            return abac.getSubject();
        }
        return null; // no ABAC subject → cannot compute affordance (degrade)
    }

    /** A row's prepared enrichment inputs (resolved snapshot attributes + ancestors + governing-root role). */
    private record RowContext(Map<String, Object> attributes, List<ParentRef> ancestors, RoleDefinition role) {}

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
        } catch (ReflectiveOperationException ignored) {
            // not a paged envelope
        }
        return null;
    }
}
