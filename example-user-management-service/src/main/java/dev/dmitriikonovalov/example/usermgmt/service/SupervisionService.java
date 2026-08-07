package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.ReportingEdge;
import dev.dmitriikonovalov.example.usermgmt.domain.ReportingEdgeRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <b>org-relation seam</b> (ADR 0029 §4): derives, per request, which resources a subject
 * <em>supervises</em> — the second, disjoint access path beside team membership. Nothing is
 * precomputed and nothing is cached: the closure is re-walked on every call exactly as membership is
 * re-derived, so removing a reporting edge withdraws the derived access on the very next request.
 *
 * <p>The seam's whole contract is <b>fail-closed</b>: {@link #transitiveReportsOf} is never
 * {@code null}, never throws, and returns the <b>empty set</b> on any breach — a depth-cap breach or a
 * cycle collapses the <em>whole</em> derivation rather than returning what was walked so far. A partial
 * closure is indistinguishable from a correct smaller one, so degrading to one would silently
 * under-report (a legitimate manager's page quietly shrinks) with no way for a caller to tell.
 *
 * <p><b>Reach is CONTROL-capable seats only</b> (ADR 0029 §3): a report contributes a team only where
 * they hold a seat whose rung of the shipped {@link TeamRoleCapabilities} ladder carries
 * {@code CONTROL} — {@code owner} / {@code administrator} / {@code senior}. A {@code member} or
 * {@code reader} seat (and every custom role) does not propagate, so one report's reader seat on an
 * unrelated team cannot silently widen their manager's reach into it.
 *
 * <p><b>The set difference lives elsewhere.</b> This service answers the <em>raw</em> supervised set;
 * {@code supervised := S \ M} is applied on the catalog side, which is the only place that knows both
 * sets. The endpoint contract is therefore "the targets this subject supervises", membership
 * notwithstanding.
 */
@Service
public class SupervisionService {

    private static final Logger log = LoggerFactory.getLogger(SupervisionService.class);

    /**
     * The closure depth cap, counted in <b>hops from the manager</b> — a direct report is hop 1.
     * Hops 1..{@code MAX_HOPS} <em>inclusive</em> are derived; discovering a hop
     * {@code MAX_HOPS + 1} is a breach that collapses the whole result to empty. The boundary is
     * inclusive on purpose: an off-by-one here would silently empty a legitimate manager's page
     * rather than fail loudly.
     */
    static final int MAX_HOPS = 10;

    private final ReportingEdgeRepository edges;
    private final UserRepository users;
    private final TeamMembershipRepository memberships;
    private final TeamRepository teams;
    private final RoleDefinitionRepository roles;

    public SupervisionService(
            ReportingEdgeRepository edges,
            UserRepository users,
            TeamMembershipRepository memberships,
            TeamRepository teams,
            RoleDefinitionRepository roles) {
        this.edges = edges;
        this.users = users;
        this.memberships = memberships;
        this.teams = teams;
        this.roles = roles;
    }

    /**
     * Every user reachable from {@code managerId} through the reporting relation, transitively —
     * <b>manager-exclusive</b> (the manager is never in their own report set).
     *
     * <p>Breadth-first with a visited set, so a re-convergent org (two managers sharing a report)
     * yields that report <em>once</em> and the walk always terminates. Two breaches collapse the
     * result to the <b>empty set</b> plus one WARN, never to a partial closure:
     *
     * <ul>
     *   <li>a <b>depth-cap breach</b> — a new report discovered beyond {@link #MAX_HOPS} hops;</li>
     *   <li>a <b>cycle</b> within the walked subgraph — rejected on write, so its presence at read
     *       time means the data is corrupt and no answer derived from it can be trusted.</li>
     * </ul>
     *
     * @return the transitive reports; empty when there are none, and empty on any breach
     */
    @Transactional(readOnly = true)
    public Set<UUID> transitiveReportsOf(UUID managerId) {
        if (managerId == null) {
            return Set.of();
        }
        Set<UUID> reports = new LinkedHashSet<>();
        // Every edge the walk actually traversed, including those landing on an already-visited node
        // or back on the manager — those are precisely the edges that reveal a cycle.
        Map<UUID, List<UUID>> walked = new HashMap<>();
        Set<UUID> frontier = Set.of(managerId);

        for (int hop = 1; !frontier.isEmpty(); hop++) {
            Set<UUID> next = new LinkedHashSet<>();
            for (ReportingEdge edge : edges.findByManagerIdIn(frontier)) {
                walked.computeIfAbsent(edge.getManagerId(), _ -> new ArrayList<>())
                        .add(edge.getReportId());
                boolean known = edge.getReportId().equals(managerId) || reports.contains(edge.getReportId());
                if (!known) {
                    next.add(edge.getReportId());
                }
            }
            if (next.isEmpty()) {
                break; // the closure is complete
            }
            if (hop > MAX_HOPS) {
                log.warn(
                        "Reporting closure for manager {} exceeds the {}-hop cap — collapsing to empty "
                                + "(a partial closure is indistinguishable from a correct smaller one)",
                        managerId, MAX_HOPS);
                return Set.of();
            }
            reports.addAll(next);
            frontier = next;
        }

        if (hasCycle(managerId, reports, walked)) {
            log.warn(
                    "Reporting relation reachable from manager {} contains a cycle — collapsing to empty "
                            + "(cycles are rejected on write; one here means the data is corrupt)",
                    managerId);
            return Set.of();
        }
        // Insertion-ordered (breadth-first) so the derived target list downstream is deterministic.
        return Collections.unmodifiableSet(reports);
    }

    /**
     * The <b>supervised target ids</b> of {@code resourceType} for a subject: the transitive reports'
     * teams, filtered to CONTROL-capable seats, projected to those teams' governed targets.
     *
     * <p>Distinct by id (two reports holding CONTROL-capable seats on the same team contribute it
     * once). Returns an <b>empty list</b> — never an error — for an unknown subject, a subject with no
     * reports, reports on no team of that type, and on <em>any</em> breach of the closure. A
     * membership whose role row cannot be resolved contributes nothing (dropped, never defaulted).
     *
     * @param subject      the IdP subject ({@code sub}) the catalog forwards (not the internal user id)
     * @param resourceType the team-target type to collect (e.g. {@code "catalog"})
     */
    @Transactional(readOnly = true)
    public List<UUID> supervisedTargets(String subject, String resourceType) {
        Optional<User> manager = users.findBySubject(subject);
        if (manager.isEmpty()) {
            return List.of();
        }
        Set<UUID> reports = transitiveReportsOf(manager.get().getId());
        if (reports.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> targetIds = new LinkedHashSet<>(); // insertion-ordered + de-duplicated
        for (UUID reportId : reports) {
            for (TeamMembership m : memberships.findByUserId(reportId)) {
                if (!isControlCapable(m)) {
                    continue; // a MEMBER/READER (or custom) seat does not propagate — ADR 0029 §3
                }
                teams.findById(m.getTeamId())
                        .filter(t -> t.getTargetType().equals(resourceType))
                        .ifPresent(t -> targetIds.add(t.getTargetId()));
            }
        }
        return List.copyOf(targetIds);
    }

    /**
     * Replace a manager's <b>whole</b> edge set with the posted one — the fixture seam behind
     * {@code POST /internal/bootstrap/reporting-edges}. Declarative on purpose: the shipped bootstrap
     * endpoints are all upsert-only, but proving <em>liveness</em> (revoking a report withdraws access
     * on the next request) needs removal, and a declarative set is the narrowest thing that provides
     * it — posting an empty list removes the manager's edges. Idempotent: the same set posted twice
     * converges to the same rows.
     *
     * <p>Rejected (422) <b>before anything is written</b>: a self-edge, and any edge that would close a
     * cycle (i.e. the manager is already reachable from a proposed report). Validation runs over the
     * <em>prospective</em> graph, so a rejected post leaves the relation exactly as it was.
     *
     * @return the number of edges written (the posted set, de-duplicated and null-stripped)
     */
    @Transactional
    public int replaceReportsOf(UUID managerId, List<UUID> reportIds) {
        if (managerId == null) {
            throw new IllegalArgumentException("managerId is required");
        }
        Set<UUID> proposed = new LinkedHashSet<>(reportIds == null ? List.of() : reportIds);
        proposed.remove(null);
        for (UUID reportId : proposed) {
            if (reportId.equals(managerId)) {
                throw new InvalidReportingEdgeException(
                        "A user cannot report to themselves: " + managerId);
            }
            if (reaches(reportId, managerId)) {
                throw new InvalidReportingEdgeException(
                        "Edge " + managerId + " → " + reportId + " would close a reporting cycle");
            }
        }
        edges.deleteAll(edges.findByManagerId(managerId));
        edges.flush(); // the delete must hit the DB before the re-insert, or uq_reporting_edge_pair trips
        for (UUID reportId : proposed) {
            edges.save(new ReportingEdge(UUID.randomUUID(), managerId, reportId));
        }
        return proposed.size();
    }

    /** Whether {@code m}'s bound role is CONTROL-capable; an unresolvable role row is not. */
    private boolean isControlCapable(TeamMembership m) {
        return roles.findById(m.getRoleDefinitionId())
                .map(RoleDefinitionEntity::getCode)
                .map(TeamRoleCapabilities::isControlCapable)
                .orElse(false);
    }

    /**
     * Whether {@code target} is reachable from {@code from} through the existing relation — the
     * write-time cycle guard. Depth-unbounded but visited-set guarded, so it terminates even if the
     * stored data already contains a cycle.
     */
    private boolean reaches(UUID from, UUID target) {
        Set<UUID> seen = new LinkedHashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(from);
        seen.add(from);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            for (ReportingEdge edge : edges.findByManagerId(current)) {
                if (edge.getReportId().equals(target)) {
                    return true;
                }
                if (seen.add(edge.getReportId())) {
                    queue.add(edge.getReportId());
                }
            }
        }
        return false;
    }

    /**
     * Whether the walked subgraph (the manager plus their reachable reports, over the edges the BFS
     * actually traversed) contains a cycle — Kahn's algorithm: peel nodes with no remaining incoming
     * edge; anything left is on a cycle.
     *
     * <p>This is what separates a genuine cycle from ordinary <b>re-convergence</b> (two managers
     * sharing one report), which is legitimate and must NOT collapse the derivation. A visited-set BFS
     * alone cannot tell the two apart — it sees "already visited" in both cases.
     */
    private static boolean hasCycle(UUID managerId, Set<UUID> reports, Map<UUID, List<UUID>> walked) {
        Set<UUID> nodes = new LinkedHashSet<>();
        nodes.add(managerId);
        nodes.addAll(reports);

        Map<UUID, Integer> inDegree = inDegrees(nodes, walked);
        Deque<UUID> ready = new ArrayDeque<>();
        inDegree.forEach((node, degree) -> {
            if (degree == 0) {
                ready.add(node);
            }
        });
        int peeled = 0;
        while (!ready.isEmpty()) {
            peeled++;
            for (UUID to : successors(ready.poll(), nodes, walked)) {
                if (inDegree.merge(to, -1, Integer::sum) == 0) {
                    ready.add(to);
                }
            }
        }
        return peeled < nodes.size(); // anything unpeelable sits on a cycle
    }

    /** Every node's incoming-edge count within the walked subgraph (zero-initialised for all nodes). */
    private static Map<UUID, Integer> inDegrees(Set<UUID> nodes, Map<UUID, List<UUID>> walked) {
        Map<UUID, Integer> inDegree = new HashMap<>();
        nodes.forEach(node -> inDegree.put(node, 0));
        for (UUID from : nodes) {
            for (UUID to : successors(from, nodes, walked)) {
                inDegree.merge(to, 1, Integer::sum);
            }
        }
        return inDegree;
    }

    /** A node's walked successors, confined to the subgraph under test. */
    private static List<UUID> successors(UUID from, Set<UUID> nodes, Map<UUID, List<UUID>> walked) {
        return walked.getOrDefault(from, List.of()).stream().filter(nodes::contains).toList();
    }
}
