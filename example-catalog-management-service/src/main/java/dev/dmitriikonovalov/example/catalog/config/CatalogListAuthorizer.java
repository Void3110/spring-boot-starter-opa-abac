package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.data.filter.AbacQueryService;
import dev.dmitriikonovalov.opaabac.data.filter.GovernedScopeResolver;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The <b>list-filtering</b> authority for the catalog (root) list — example-app code on the library's
 * public {@link AbacQueryService} (Slice B4, ADR 0018; extended by ADR 0029). It is the <b>sole</b>
 * authority for {@code GET /catalogs}: there is no coarse {@code @OpaPreAuthorize(catalog:list)} gate on
 * the endpoint anymore (a type-level list resolves no per-resource role, and after B4 there is no realm
 * fallback, so such a gate would deny every membership-driven caller). Authorization is instead the
 * <b>scope ∧ residual</b> cut here, fail-closed to an empty page.
 *
 * <h2>Two disjoint access paths</h2>
 * A Catalog is a <b>root</b> — its visibility is a pure <em>membership</em> question (which team governs
 * it, am I a member?), which OPA partial-eval cannot see on the row — so the id set is supplied app-side.
 * Since ADR 0029 there are <b>two</b> such sets, and they are made <b>disjoint by construction</b>:
 * <ul>
 *   <li><b>M</b> — {@link GovernedScopeResolver#governedIds}: the catalogs the subject governs through
 *       <em>team membership</em>.</li>
 *   <li><b>supervised = S \ M</b> — {@link SupervisedScopeClient#supervisedIds} (the catalogs the subject
 *       supervises through the reporting structure) <b>reduced by M</b>. <b>Membership always wins.</b>
 *       The reduction is load-bearing twice: a dual-hatted manager must not be pushed onto the stricter
 *       branch for their own team's data, and it makes each row's provenance unambiguous — so the
 *       supervisor role's <em>vacuous</em> tag requirement can never end up judging a tag-gated
 *       membership row. Unioning the two roles' permissions is exactly that fail-open, and is rejected.</li>
 * </ul>
 *
 * <h2>Which role drives the residual — the rule that replaced {@code governedIds.get(0)}</h2>
 * {@link AbacQueryService#findAuthorized} compiles <b>exactly one</b> residual from the single
 * {@link AbacContext} it is given; there is no overload taking two {@code (scope, context)} legs. So the
 * anchor the role is resolved on decides which authority judges the residual, and picking it wrongly is
 * this slice's one fail-<em>open</em>:
 * <ul>
 *   <li><b>Whenever M is non-empty the anchor is a MEMBERSHIP id</b> — never one from the
 *       {@code M ∪ supervised} union. A supervised id selecting it would let the supervisor role's
 *       vacuous tag requirement judge tag-gated membership rows.</li>
 *   <li><b>Only when M is empty</b> — the pure-supervisor case — is the anchor a supervised id, which is
 *       correct precisely because there are no membership rows for it to widen.</li>
 * </ul>
 * The three shapes, all using the <b>shipped</b> paged 5-arg call (the ADR-0010 base-scope-widening
 * idiom, reused rather than reinvented):
 *
 * <table>
 *   <caption>Composition by case</caption>
 *   <tr><th>Case</th><th>{@code scope}</th><th>context role</th><th>{@code subtreeSpec}</th></tr>
 *   <tr><td>both non-empty</td><td>{@code id IN (M ∪ supervised)}</td><td>the <b>membership</b> role</td>
 *       <td>{@code id IN supervised}</td></tr>
 *   <tr><td>M empty (a pure supervisor)</td><td>{@code id IN supervised}</td>
 *       <td>the <b>supervisor</b> role</td><td>{@code null}</td></tr>
 *   <tr><td>supervised empty (an ordinary member)</td><td>{@code id IN M} — <b>today's call,
 *       unchanged</b></td><td>the membership role</td><td>{@code null}</td></tr>
 * </table>
 *
 * The library composes {@code scope ∧ (residual ∨ subtreeSpec) ∧ notDenied()} on its <b>pure-SQL</b>
 * branch, so in the mixed case a membership row is judged by the membership residual while a supervised
 * row is admitted by the widening arm — each row judged by the authority that earned it, with the
 * deny-override still AND-ed outside. Handing {@code findAuthorized} a pre-composed {@code legA.or(legB)}
 * as {@code scope} would instead AND that one residual over the whole union, narrowing supervised rows by
 * the membership role; the {@code subtreeSpec} slot is the shipped way to express "admit these rows too".
 *
 * <p><b>Precondition, asserted (U34) rather than assumed:</b> admitting supervised rows through
 * {@code subtreeSpec} is correct <em>only because the supervisor role's residual is unconditional</em>
 * ({@code READ} with empty {@code requiredTags} → {@code ALLOW_ALL}). If a later slice gives that role a
 * tag requirement, this composition must change — U34 is what makes the coupling visible.
 *
 * <p><b>Recorded limitation (U42), documented not detected.</b> {@code subtreeSpec} reaches the query on
 * the pure-SQL branch only. With partial-eval <em>disabled</em>, or on the {@code !fullySupported()} +
 * allowlist fallback, it is ignored and a <b>mixed</b> subject's supervised rows are decided by the
 * <em>membership</em> role's verdict rather than by the supervised arm. A <b>pure supervisor is
 * unaffected</b> — its ids ride {@code scope} with the supervisor role as context, so all four branches
 * judge those rows correctly — and an ordinary member is byte-identical to today. Correcting the mixed
 * case needs a library change this slice forbids end to end, and the app cannot observe which branch ran
 * ({@code AbacQueryService} exposes neither its settings nor the compiled residual), so no WARN and no
 * run-time behavior is specified here. See {@code 00-DESIGN} §5.
 *
 * <h2>Fail-closed (ADR 0018 §5, ADR 0029 §Fail-closed posture)</h2>
 * Unauthenticated → empty page. No {@link AbacQueryService} bean (the starter off) → empty page. No
 * {@link GovernedScopeResolver} bean (e.g. the demo profile) → empty page. Both scopes empty → empty
 * page. An <b>unresolvable role on either leg</b> (a role-source outage, or a role revoked between the
 * two calls) → that leg contributes nothing and is <b>dropped, never defaulted</b> to a fallback role;
 * when no leg has authority the page is empty, exactly as a {@code null} role compiling the residual to
 * {@code DENY_ALL} would produce, without the needless round-trip. A
 * <b>supervised-source failure</b> degrades to <b>membership-only</b> — the client already fails closed to
 * an empty list, so the second leg simply is not there. In every branch the floor is the empty page, never
 * a partial supervised set and never the whole table.
 */
@Component
public class CatalogListAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(CatalogListAuthorizer.class);

    /**
     * The supervised-read audit channel — a <b>dedicated, separately-routable</b> SLF4J logger, named
     * explicitly (not class-derived) so a consumer can route it independently of this class's own logs and
     * so a test can assert it by name. Retention and routing are the consumer's; nothing is persisted here.
     */
    private static final Logger supervisedReadAudit =
            LoggerFactory.getLogger("dev.dmitriikonovalov.example.catalog.audit.SupervisedRead");

    /** The one resource type this authorizer lists — the coordinate every seam it calls is keyed by. */
    private static final String CATALOG_TYPE = "catalog";

    /**
     * The wire claim an agent client's token carries (the {@code catalog-agent-*} clients' protocol
     * mapper). Its <b>presence</b> is what marks a delegated call; the value is never interpreted here.
     * Note the name: {@code actor} is the MCP server's internal tool-gate attribute and never travels
     * downstream, so this service would never see it.
     */
    private static final String AGENT_DELEGATION_CLAIM = "act_chain";

    private final CatalogRepository catalogs;
    private final RoleDefinitionSupplier roleDefinitionSupplier;
    /** Absent when the starter is off (opa.abac.enabled=false, the unguarded-baseline rig) → empty. */
    private final ObjectProvider<AbacQueryService> queryService;
    /** Present only when membership-driven isolation is wired (catalog.role-source=http); absent → empty. */
    private final ObjectProvider<GovernedScopeResolver> governedScopeResolver;
    /** Present only when the user-service edge is configured; absent → the list simply has no second leg. */
    private final ObjectProvider<SupervisedScopeClient> supervisedScopeClient;

    public CatalogListAuthorizer(
            CatalogRepository catalogs,
            RoleDefinitionSupplier roleDefinitionSupplier,
            ObjectProvider<AbacQueryService> queryService,
            ObjectProvider<GovernedScopeResolver> governedScopeResolver,
            ObjectProvider<SupervisedScopeClient> supervisedScopeClient) {
        this.catalogs = catalogs;
        this.roleDefinitionSupplier = roleDefinitionSupplier;
        this.queryService = queryService;
        this.governedScopeResolver = governedScopeResolver;
        this.supervisedScopeClient = supervisedScopeClient;
    }

    /**
     * The page of catalogs the current subject may see — those they govern through team membership whose
     * membership role grants {@code list}, <b>plus</b> those they supervise (read-only, ADR 0029) —
     * windowed by {@code pageable}. The page's {@code totalElements} is the subject's authorized total
     * across <em>both</em> legs (the scope ∧ residual cut applies to the count exactly as to the rows).
     * The {@code pageable} must carry the service's fixed total order; the library seam rejects an
     * unsorted one.
     */
    public Page<CatalogEntity> readable(Pageable pageable) {
        AbacContext.Subject subject = currentSubject();
        if (subject == null) {
            return Page.empty(pageable); // unauthenticated → empty
        }

        AbacQueryService abacQuery = queryService.getIfAvailable();
        if (abacQuery == null) {
            // The starter is OFF (opa.abac.enabled=false — the unguarded-baseline rig, ADR 0021 §2):
            // no residual compiler exists, so no cut can be composed. Same fail-closed floor as every
            // other branch: the empty page, never the table.
            log.debug("catalog list empty: no AbacQueryService bean (the starter is off)");
            return Page.empty(pageable);
        }

        GovernedScopeResolver resolver = governedScopeResolver.getIfAvailable();
        if (resolver == null) {
            // No membership-driven isolation wired (e.g. demo profile) → fail-closed to empty. The library
            // default (a plain @OpaPreAuthorize(catalog:list)) is unaffected for non-adopters; this
            // example bean simply has nothing to scope by, so it returns nothing rather than the table.
            log.debug("catalog list empty: no GovernedScopeResolver bean (membership isolation not wired)");
            return Page.empty(pageable);
        }

        // ONE membership fetch and (at most) ONE supervised fetch, both per request, so the two legs read a
        // consistent id set within this request. Each is independently fail-closed to an empty list.
        List<UUID> membershipIds = distinct(resolver.governedIds(subject.id(), CATALOG_TYPE));
        List<UUID> supervisedIds = isAgentCall(subject) ? List.of() : supervisedScope(subject.id(), membershipIds);

        if (membershipIds.isEmpty() && supervisedIds.isEmpty()) {
            return Page.empty(pageable); // governs and supervises nothing → empty page (fail-closed)
        }

        return authorizedPage(subject, membershipIds, supervisedIds, abacQuery, pageable);
    }

    /**
     * Whether this request is an <b>agent-marked</b> call — the third prong of ADR 0030 Amendment 4's
     * human-only supervised path.
     *
     * <p><b>Why it lives here and not in Rego.</b> The catalog list is the two-leg query above, and its
     * cut comes from {@code filter} — which deliberately never consults {@code denied} (slice B's pin,
     * asserted positively in the policy tests). So no Rego deny can reach this leg; the closure has to be
     * app-side. The two single-decision prongs (the leaf policies' provenance-scoped agent deny) cover
     * everything else.
     *
     * <p><b>Presence, never truthiness.</b> The discriminator is the {@code act_chain} <em>key</em>:
     * a bare truthiness test would let {@code act_chain: false} — or {@code []}, or {@code ""} — route an
     * agent call down the human branch, which is the recorded escape this project has already been bitten
     * by once.
     *
     * <p><b>The degrade is the shape that already exists</b>, not a new one: dropping the supervised leg
     * is exactly what a supervised-source outage does, so an agent falls back to membership-only — which,
     * for a pure supervisor, is the empty page. Strictly narrower, never wider, and members are untouched
     * because their rows come from the membership leg this never removes.
     */
    private static boolean isAgentCall(AbacContext.Subject subject) {
        Map<String, Object> attributes = subject.attributes();
        if (attributes == null || !attributes.containsKey(AGENT_DELEGATION_CLAIM)) {
            return false;
        }
        log.debug("catalog list: agent-marked call ({} present) — the supervised leg is skipped",
                AGENT_DELEGATION_CLAIM);
        return true;
    }

    /**
     * {@code supervised := S \ M} — the raw supervised set reduced by membership, preserving order and
     * de-duplicating. <b>Membership always wins</b>, so the two scopes are disjoint by construction and a
     * doubly-reachable catalog is judged by its membership role, never by the vacuous-tag supervisor role.
     *
     * <p>Empty when no {@link SupervisedScopeClient} is wired, and empty on every supervised-source
     * failure (the client itself fails closed) — which is exactly the documented degrade to
     * <b>membership-only</b>, never wider and never partial.
     */
    private List<UUID> supervisedScope(String subjectId, List<UUID> membershipIds) {
        SupervisedScopeClient client = supervisedScopeClient.getIfAvailable();
        if (client == null) {
            return List.of();
        }
        Set<UUID> memberships = Set.copyOf(membershipIds);
        List<UUID> supervised = new ArrayList<>();
        for (UUID id : distinct(client.supervisedIds(subjectId, CATALOG_TYPE))) {
            if (!memberships.contains(id)) {
                supervised.add(id);
            }
        }
        return List.copyOf(supervised);
    }

    /** The composed page for a subject with at least one leg — see the class Javadoc's case table. */
    private Page<CatalogEntity> authorizedPage(
            AbacContext.Subject subject,
            List<UUID> membershipIds,
            List<UUID> supervisedIds,
            AbacQueryService abacQuery,
            Pageable pageable) {

        // THE ANCHOR RULE (this replaces B4's governedIds.get(0)): whenever M is non-empty the
        // residual-driving role is resolved from a MEMBERSHIP id, never from the union. Only a pure
        // supervisor resolves it from a supervised id.
        boolean membershipLeg = !membershipIds.isEmpty();
        RoleDefinition roleDefinition =
                resolveRole(subject.id(), membershipLeg ? membershipIds.get(0) : supervisedIds.get(0));

        if (membershipLeg && isSupervisedProvenance(roleDefinition)) {
            // The anchor is a MEMBERSHIP id, so the role resolved on it must be membership-derived. It is
            // not: the membership was revoked between reading the governed ids and resolving the role, and
            // the user-service's ordered fallthrough answered with the SYNTHESIZED supervisor role instead
            // (membership first, supervision second — ADR 0029 §2). Letting that role drive the residual
            // would apply its VACUOUS tag requirement to every other membership row in the scope — the
            // exact fail-open `supervised := S \ M` exists to prevent, arriving by a race rather than by
            // arithmetic. Drop the membership leg instead (it is stale by definition) and fall through to
            // the supervised-only shape below. Fail-closed: strictly narrower, never wider.
            log.debug("catalog list: the membership anchor resolved a supervised-provenance role — "
                    + "membership revoked mid-request; dropping the membership leg");
            roleDefinition = null;
        }

        if (roleDefinition == null && membershipLeg && !supervisedIds.isEmpty()) {
            // The membership role source failed, or the role no longer resolves (revoked between the two
            // calls), while the subject still supervises catalogs. The membership leg contributes NOTHING
            // — dropped, never defaulted to a fallback role — but the supervised leg stands on its own
            // authority, so degrade to the pure-supervisor shape rather than emptying the whole page.
            // Strictly narrower than the mixed page, never wider (U29).
            log.debug("catalog list: the membership leg did not resolve — supervised leg only");
            membershipLeg = false;
            roleDefinition = resolveRole(subject.id(), supervisedIds.get(0));
        }
        if (roleDefinition == null) {
            // No authority on any leg. Identical outcome to letting a null role compile the `filter`
            // residual to DENY_ALL — the empty page — without the needless OPA and repository round-trip.
            return Page.empty(pageable);
        }

        List<UUID> scopeIds = membershipLeg ? union(membershipIds, supervisedIds) : supervisedIds;
        Specification<CatalogEntity> scope = idIn(scopeIds);
        // The widening arm carries ids ONLY in the mixed case: a pure supervisor's ids already ride `scope`
        // with the supervisor role as context, so there is nothing to widen.
        Specification<CatalogEntity> subtreeSpec =
                membershipLeg && !supervisedIds.isEmpty() ? idIn(supervisedIds) : null;

        // The query context: the resource is UNKNOWN (it's the row being filtered); only the type is set so
        // the policy path resolves to `catalog`.
        AbacContext queryContext = new AbacContext(
                subject,
                "catalog:list",
                new AbacContext.Resource(CATALOG_TYPE, null, Map.of()),
                roleDefinition,
                Map.of());

        Page<CatalogEntity> page =
                abacQuery.findAuthorized(catalogs, scope, queryContext, subtreeSpec, pageable);
        auditSupervisedRead(subject, page, supervisedIds, membershipLeg);
        return page;
    }

    /**
     * Whether {@code role} carries the <b>supervised</b> provenance stamp (ADR 0031's
     * {@code attributes.provenance}) — the marker the user-service puts on the synthesized supervisor role.
     * An absent, empty or unknown value reads as "not supervised", so a deployment whose role source does
     * not stamp at all behaves exactly as before: this check can only ever <em>drop</em> a leg.
     */
    private static boolean isSupervisedProvenance(RoleDefinition role) {
        return role != null && "supervised".equals(role.attributes().get("provenance"));
    }

    /**
     * The role for {@code (subject, anchor)}, or {@code null} when it does not resolve — including a
     * role-source outage, which is caught here so it lands on the fail-closed floor rather than becoming
     * a 500.
     */
    private RoleDefinition resolveRole(String subjectId, UUID anchor) {
        try {
            return roleDefinitionSupplier.lookup(subjectId, CATALOG_TYPE, anchor.toString()).orElse(null);
        } catch (RoleResolutionException e) {
            log.debug("catalog list: role-source outage ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Emit <b>one</b> audit event per supervised list <b>request</b>, at {@code INFO}, on the dedicated
     * {@code …catalog.audit.SupervisedRead} logger — and <b>only when the supervised leg contributed at
     * least one row</b>. A request whose supervised leg is empty emits nothing (otherwise every ordinary
     * list by a supervisor-eligible subject would log), and an ordinary membership read emits nothing at
     * all.
     *
     * <p>The payload carries the subject, the access path, and the supervised root ids <b>as a list</b> —
     * plural, because a page can span several supervised roots, which a singular "root id" cannot express.
     * Nothing is persisted; retention and routing are the consumer's.
     *
     * <p><b>Scope, pinned:</b> this slice audits the <b>list</b> path only. The supervised authority
     * is applied on single-{@code GET}s too (the synthesized role passes the generic
     * {@code @OpaPreAuthorize} gate), but that shared decision path has no supervised-specific
     * emission point — nothing there distinguishes a supervisor's read from a member's. Adding one
     * is the slice-C audit work; this slice only audits where it composes the page itself.
     */
    private static void auditSupervisedRead(
            AbacContext.Subject subject,
            Page<CatalogEntity> page,
            List<UUID> supervisedIds,
            boolean membershipLeg) {
        if (supervisedIds.isEmpty()) {
            return;
        }
        Set<UUID> supervised = Set.copyOf(supervisedIds);
        List<UUID> onPage = page.getContent().stream()
                .map(CatalogEntity::getId)
                .filter(supervised::contains)
                .toList();
        if (onPage.isEmpty()) {
            return; // the supervised leg contributed no row to this page → no event
        }
        supervisedReadAudit.info(
                "supervised catalog list read subject={} accessPath={} supervisedRootIds={}",
                subject.id(),
                membershipLeg ? "mixed" : "supervised",
                onPage);
    }

    /** {@code M ∪ supervised}, order-stable and de-duplicated (the two are already disjoint). */
    private static List<UUID> union(List<UUID> membershipIds, List<UUID> supervisedIds) {
        Set<UUID> all = new LinkedHashSet<>(membershipIds);
        all.addAll(supervisedIds);
        return List.copyOf(all);
    }

    /** {@code id IN (ids)} — the AND-gate nothing escapes (or, in the subtree slot, the widening arm). */
    private static Specification<CatalogEntity> idIn(List<UUID> ids) {
        return (root, query, cb) -> root.get("id").in(ids);
    }

    /**
     * Order-stable de-duplication — a repeated id would otherwise be listed once but counted twice —
     * with {@code null} elements dropped. Neither shipped source can emit a {@code null} (both parse
     * UUIDs and discard the whole result on a malformed element), but a scope source is an SPI: a
     * {@code null} slipping through must narrow the scope, never become an unhandled 500.
     */
    private static List<UUID> distinct(List<UUID> ids) {
        Set<UUID> seen = new LinkedHashSet<>(ids);
        seen.remove(null);
        return List.copyOf(seen);
    }

    private static AbacContext.Subject currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            return abac.getSubject();
        }
        return null;
    }
}
