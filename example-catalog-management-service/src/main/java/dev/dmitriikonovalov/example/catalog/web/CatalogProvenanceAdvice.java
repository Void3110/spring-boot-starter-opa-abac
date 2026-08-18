package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.openapi.model.Catalog;
import dev.dmitriikonovalov.example.catalog.openapi.model.CatalogPage;
import dev.dmitriikonovalov.example.catalog.security.CatalogProvenanceCarrier;
import dev.dmitriikonovalov.example.catalog.security.CatalogProvenanceMemo;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Attaches {@code _provenance} to catalog responses — <em>by which access path is this row in front
 * of you?</em> — after the handler returns (ADR 0033). <strong>Affordance, not enforcement</strong>:
 * a client uses it to explain and to predict; the gate still decides independently.
 *
 * <h2>One semantic, two derivations</h2>
 * <ul>
 *   <li><b>The list</b> reads the <em>leg</em>: {@link CatalogProvenanceMemo} carries the supervised
 *       id set the {@code CatalogListAuthorizer} composed the query from, so a row in that set came
 *       by supervision and every other row by membership. No per-row role lookup — that would
 *       reintroduce the per-row resolve amplification Phase 7.3 removed, and the leg <em>is</em> the
 *       list's truth.</li>
 *   <li><b>The single GET</b> reads the <em>stamp</em>: the role the gate resolved on this catalog
 *       carries {@code attributes.provenance} (ADR 0031). A request-memo hit under the default-on
 *       {@code opa.abac.resolve-memo.enabled} — the manager's key for a catalog GET is
 *       {@code (subject, "catalog", catalogId)} because a root's ancestor list is empty — so the
 *       lookup costs nothing on the shipped config. With the memo off it is one extra round trip per
 *       GET: documented (ADR 0033 §6), not engineered around.</li>
 * </ul>
 * They agree by construction: the user-service synthesizes the supervised role <em>only</em> on the
 * non-membership branch (ADR 0029), so a catalog is in {@code S \ M} iff its resolved role is
 * stamped {@code supervised}.
 *
 * <h2>Absent when not computed</h2>
 * The value is omitted — never {@code null} on the wire, never a default — whenever the server did
 * not establish it, because an absent field must mean <em>unknown</em> and not {@code "member"}:
 * <ul>
 *   <li><b>list</b>: no memo attribute at all (a body that never passed through the two-leg
 *       authorizer's query path). A memo that is <b>present but empty</b> is a real answer — no
 *       supervised leg on this page — and every row is labelled {@code "member"}; the read tests
 *       {@link Optional#isPresent()}, never emptiness;</li>
 *   <li><b>GET</b>: no subject, no resolved role, a role carrying no {@code provenance} stamp, a
 *       stamp outside the vocabulary, or any exception from the lookup.</li>
 * </ul>
 * Every degrade branch that <em>can</em> be labelled honestly still is: an agent-marked call (the
 * supervised leg is skipped, so the memo is empty and the rows really are membership), a
 * membership-leg outage (the page degraded to supervised-only, and the memo is exactly those ids), a
 * supervised-source outage (an empty supervised set, so the survivors really are membership).
 *
 * <p><strong>Never throws.</strong> A failure here must not turn a successful read into a 500 — the
 * label is decoration on a response the gate already allowed. Independent of
 * {@code ActionEnrichmentAdvice}: each stamps its own property, in either order.
 */
@RestControllerAdvice
public class CatalogProvenanceAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(CatalogProvenanceAdvice.class);

    private static final String CATALOG_TYPE = "catalog";
    /** The role-attribute stamp the user-service puts on the synthesized supervisor role (ADR 0031). */
    private static final String STAMP_SUPERVISED = "supervised";
    /** The stamp on an ordinary membership-derived role. */
    private static final String STAMP_MEMBERSHIP = "membership";

    private final RoleDefinitionSupplier roleDefinitionSupplier;

    public CatalogProvenanceAdvice(RoleDefinitionSupplier roleDefinitionSupplier) {
        this.roleDefinitionSupplier = roleDefinitionSupplier;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Cheap and unconditional: the body itself is inspected in beforeBodyWrite. A body that is
        // neither a CatalogPage nor a single Catalog is returned untouched, with no work done.
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
        try {
            if (body instanceof CatalogPage page) {
                stampPage(page);
            } else if (body instanceof Catalog catalog && isSingleCatalogRead(request)) {
                stampSingle(catalog);
            }
        } catch (RuntimeException e) {
            // Decoration must never break a read the gate already allowed. Omit and move on.
            log.debug("catalog provenance: omitted ({})", e.getClass().getSimpleName());
        }
        return body;
    }

    /**
     * Only the single-catalog <b>read</b> derives a label. {@code createCatalog} also returns a bare
     * {@code Catalog}, but its gate is type-level (a {@code null} resource id), so a lookup by the
     * brand-new id would be a guaranteed memo <em>miss</em> — a real user-service round trip on every
     * create, to label a row the caller just made. {@code updateCatalog} is left alone for the same
     * reason and because the affordance is a read-side label. Keyed on the HTTP verb rather than the
     * handler's name so a rename cannot silently re-enable it.
     */
    private static boolean isSingleCatalogRead(ServerHttpRequest request) {
        return HttpMethod.GET.equals(request.getMethod());
    }

    /** List derivation: id ∈ the supervised set ⇒ supervised, else member; no memo ⇒ omit every row. */
    private static void stampPage(CatalogPage page) {
        Optional<Set<UUID>> supervised = CatalogProvenanceMemo.read();
        if (supervised.isEmpty()) {
            return; // never computed → every row keeps a null provenance and serializes without the key
        }
        // No null-guard on getItems(): the generated envelope initializes it to an empty list and never
        // nulls it. Were that ever to change, beforeBodyWrite's catch turns the NPE into the same omit.
        Set<UUID> supervisedIds = supervised.get();
        for (Catalog item : page.getItems()) {
            if (item == null || item.getId() == null) {
                continue;
            }
            item.setProvenance(
                    supervisedIds.contains(item.getId())
                            ? CatalogProvenanceCarrier.SUPERVISED
                            : CatalogProvenanceCarrier.MEMBER);
        }
    }

    /** GET derivation: map the resolved role's provenance stamp; anything else omits. */
    private void stampSingle(Catalog catalog) {
        if (catalog.getId() == null) {
            return;
        }
        AbacContext.Subject subject = currentSubject();
        if (subject == null) {
            return; // no ABAC subject → nothing to look a role up for
        }
        String label;
        try {
            label = roleDefinitionSupplier
                    .lookup(subject.id(), CATALOG_TYPE, catalog.getId().toString())
                    .map(CatalogProvenanceAdvice::labelOf)
                    .orElse(null);
        } catch (Exception e) {
            // A role-source outage (or anything else) leaves the value UNKNOWN — omit, never guess.
            log.debug("catalog provenance: role lookup failed for {} ({})",
                    catalog.getId(), e.getClass().getSimpleName());
            return;
        }
        if (label != null) {
            catalog.setProvenance(label);
        }
    }

    /** The stamp → wire vocabulary map. An absent, empty or unrecognized stamp yields {@code null} (omit). */
    private static String labelOf(RoleDefinition role) {
        Object stamp = role.attributes().get("provenance");
        if (STAMP_SUPERVISED.equals(stamp)) {
            return CatalogProvenanceCarrier.SUPERVISED;
        }
        if (STAMP_MEMBERSHIP.equals(stamp)) {
            return CatalogProvenanceCarrier.MEMBER;
        }
        return null;
    }

    /**
     * The subject, the way the existing advices and authorizers read it — {@link SecurityContextHolder}
     * → {@link AbacAuthentication}. There is no injectable shared helper in this repo and this slice
     * adds none; the idiom is copied deliberately rather than extracted.
     */
    private static AbacContext.Subject currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            return abac.getSubject();
        }
        return null;
    }
}
