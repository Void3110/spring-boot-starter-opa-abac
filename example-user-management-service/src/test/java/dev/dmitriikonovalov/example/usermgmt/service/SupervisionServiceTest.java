package dev.dmitriikonovalov.example.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.example.usermgmt.domain.ReportingEdge;
import dev.dmitriikonovalov.example.usermgmt.domain.ReportingEdgeRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * T1 unit cases (U1–U10, U33) for the org-relation seam — the transitive derivation, its two
 * fail-closed breaches (depth cap, cycle), the CONTROL-capable reach rule, and the write-time
 * rejections. Plain unit test over stubbed repositories (no Spring context), mirroring
 * {@code InternalBootstrapUpsertTest}'s shape.
 *
 * <p>The stub graph is an in-memory edge list so the BFS is exercised for real; only the repository
 * boundary is mocked.
 */
class SupervisionServiceTest {

    private final ReportingEdgeRepository edges = mock(ReportingEdgeRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TeamMembershipRepository memberships = mock(TeamMembershipRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final RoleDefinitionRepository roles = mock(RoleDefinitionRepository.class);

    private final SupervisionService service =
            new SupervisionService(edges, users, memberships, teams, roles);

    /** The stored edge list the mocked repository answers from. */
    private final List<ReportingEdge> graph = new ArrayList<>();

    @BeforeEach
    void wireGraph() {
        when(edges.findByManagerIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<?> managerIds = inv.getArgument(0);
            return graph.stream().filter(e -> managerIds.contains(e.getManagerId())).toList();
        });
        when(edges.findByManagerId(any(UUID.class))).thenAnswer(inv -> {
            UUID managerId = inv.getArgument(0);
            return graph.stream().filter(e -> e.getManagerId().equals(managerId)).toList();
        });
    }

    private void edge(UUID manager, UUID report) {
        graph.add(new ReportingEdge(UUID.randomUUID(), manager, report));
    }

    private static UUID id() {
        return UUID.randomUUID();
    }

    // --- U1, U2: the transitive walk -------------------------------------------------------------

    @Test // U1 — a 3-level chain resolves transitively and is manager-EXCLUSIVE
    void transitiveWalkReturnsEveryReportButNotTheManager() {
        UUID anna = id();
        UUID bob = id();
        UUID carol = id();
        UUID dave = id();
        edge(anna, bob);
        edge(anna, carol);
        edge(carol, dave);

        assertThat(service.transitiveReportsOf(anna)).containsExactlyInAnyOrder(bob, carol, dave);
        assertThat(service.transitiveReportsOf(anna)).doesNotContain(anna);
    }

    @Test // U2 — no outgoing edges → empty, never null, no throw
    void subjectWithNoEdgesResolvesEmpty() {
        assertThat(service.transitiveReportsOf(id())).isEmpty();
        assertThat(service.transitiveReportsOf(null)).isEmpty();
    }

    @Test // re-convergence is NOT a cycle: two managers sharing a report yields it once
    void reconvergentOrgYieldsTheSharedReportOnce() {
        UUID anna = id();
        UUID bob = id();
        UUID carol = id();
        UUID dave = id();
        edge(anna, bob);
        edge(anna, carol);
        edge(bob, dave);
        edge(carol, dave); // dave reports to both bob and carol — a diamond, not a cycle

        assertThat(service.transitiveReportsOf(anna)).containsExactlyInAnyOrder(bob, carol, dave);
    }

    // --- U3, U33: the depth cap, inclusive at 10 -------------------------------------------------

    /** A chain manager → h1 → h2 → … → h{hops}; returns the manager. */
    private UUID chain(int hops) {
        UUID manager = id();
        UUID current = manager;
        for (int i = 0; i < hops; i++) {
            UUID next = id();
            edge(current, next);
            current = next;
        }
        return manager;
    }

    @Test // U33 — EXACTLY 10 hops is the inclusive boundary: resolves fully, no collapse
    void chainOfExactlyTenHopsResolvesFully() {
        UUID manager = chain(10);
        assertThat(service.transitiveReportsOf(manager)).hasSize(10);
    }

    @Test // U3 — an 11th hop is a cap breach: the WHOLE set collapses, not a partial 10-hop set
    void chainOfElevenHopsCollapsesToEmpty() {
        UUID manager = chain(11);
        assertThat(service.transitiveReportsOf(manager)).isEmpty();
    }

    // --- U4: a read-time cycle fails closed ------------------------------------------------------

    @Test // U4 — a → b → a terminates and fails closed to empty (never a partial set)
    void cycleThroughTheManagerCollapsesToEmpty() {
        UUID a = id();
        UUID b = id();
        edge(a, b);
        edge(b, a);

        assertThat(service.transitiveReportsOf(a)).isEmpty();
    }

    @Test // U4 — a cycle DOWNSTREAM of the manager (b → c → b) also collapses, and terminates
    void cycleBelowTheManagerCollapsesToEmpty() {
        UUID a = id();
        UUID b = id();
        UUID c = id();
        edge(a, b);
        edge(b, c);
        edge(c, b);

        assertThat(service.transitiveReportsOf(a)).isEmpty();
    }

    // --- U5, U6, U7: write-time rejection + idempotency ------------------------------------------

    @Test // U5 — an edge closing a cycle is rejected on write; nothing is persisted
    void edgeClosingACycleIsRejectedOnWrite() {
        UUID a = id();
        UUID b = id();
        edge(a, b); // a already manages b
        List<UUID> closesTheCycle = List.of(a);

        assertThatThrownBy(() -> service.replaceReportsOf(b, closesTheCycle))
                .isInstanceOf(InvalidReportingEdgeException.class)
                .hasMessageContaining("cycle");
        verify(edges, never()).save(any());
        verify(edges, never()).deleteAll(any());
    }

    @Test // U6 — a self-edge is rejected on write
    void selfEdgeIsRejectedOnWrite() {
        UUID a = id();
        List<UUID> selfEdge = List.of(a);
        assertThatThrownBy(() -> service.replaceReportsOf(a, selfEdge))
                .isInstanceOf(InvalidReportingEdgeException.class)
                .hasMessageContaining("themselves");
        verify(edges, never()).save(any());
    }

    @Test // U7 — the same edge posted twice converges to ONE row (declarative replace, idempotent)
    void duplicateEdgeInOnePostIsWrittenOnce() {
        UUID a = id();
        UUID b = id();

        service.replaceReportsOf(a, List.of(b, b));

        ArgumentCaptor<ReportingEdge> saved = ArgumentCaptor.forClass(ReportingEdge.class);
        verify(edges, times(1)).save(saved.capture()); // exactly one row, not two
        assertThat(saved.getValue().getManagerId()).isEqualTo(a);
        assertThat(saved.getValue().getReportId()).isEqualTo(b);
    }

    @Test // U7 — an empty posted set removes the manager's edges (the E4 liveness seam)
    void emptyPostedSetRemovesTheManagersEdges() {
        UUID a = id();
        UUID b = id();
        edge(a, b);

        service.replaceReportsOf(a, List.of());

        ArgumentCaptor<Iterable<ReportingEdge>> deleted = ArgumentCaptor.captor();
        verify(edges).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).singleElement()
                .satisfies(e -> assertThat(e.getReportId()).isEqualTo(b));
        verify(edges, never()).save(any());
    }

    @Test // a null element in the posted list is stripped, never an NPE reaching the caller as a 500
    void nullReportIdIsStrippedNotThrown() {
        UUID a = id();
        UUID b = id();
        List<UUID> withNull = new ArrayList<>();
        withNull.add(b);
        withNull.add(null);

        assertThat(service.replaceReportsOf(a, withNull)).isEqualTo(1);
        assertThat(service.replaceReportsOf(a, null)).isZero();
    }

    // --- U8, U9, U10: the CONTROL-capable reach rule ---------------------------------------------

    private RoleDefinitionEntity systemRole(UUID roleId, String code) {
        RoleDefinitionEntity role = new RoleDefinitionEntity(
                roleId, code, true, null, Map.of(), Map.of("*", List.of("READ")));
        when(roles.findById(roleId)).thenReturn(Optional.of(role));
        return role;
    }

    private void member(UUID userId, UUID teamId, UUID roleId) {
        List<TeamMembership> existing = new ArrayList<>(memberships.findByUserId(userId));
        existing.add(new TeamMembership(UUID.randomUUID(), teamId, userId, roleId));
        when(memberships.findByUserId(userId)).thenReturn(existing);
    }

    private UUID team(String targetType, UUID targetId) {
        UUID teamId = id();
        when(teams.findById(teamId))
                .thenReturn(Optional.of(new Team(teamId, "T", targetType, targetId)));
        return teamId;
    }

    private UUID managerOf(UUID report) {
        UUID manager = id();
        String subject = "sub-" + manager;
        when(users.findBySubject(subject))
                .thenReturn(Optional.of(new User(manager, subject, "Manager")));
        edge(manager, report);
        return manager;
    }

    @Test // U8 — only OWNER/ADMINISTRATOR/SENIOR seats propagate; MEMBER and READER do not
    void onlyControlCapableSeatsPropagate() {
        UUID report = id();
        UUID manager = managerOf(report);

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        UUID t4 = UUID.randomUUID();
        UUID t5 = UUID.randomUUID();
        systemRole(SystemRoles.OWNER_ID, SystemRoles.OWNER);
        systemRole(SystemRoles.ADMINISTRATOR_ID, SystemRoles.ADMINISTRATOR);
        systemRole(SystemRoles.SENIOR_ID, SystemRoles.SENIOR);
        systemRole(SystemRoles.MEMBER_ID, SystemRoles.MEMBER);
        systemRole(SystemRoles.READER_ID, SystemRoles.READER);
        member(report, team("catalog", t1), SystemRoles.OWNER_ID);
        member(report, team("catalog", t2), SystemRoles.ADMINISTRATOR_ID);
        member(report, team("catalog", t3), SystemRoles.SENIOR_ID);
        member(report, team("catalog", t4), SystemRoles.MEMBER_ID);
        member(report, team("catalog", t5), SystemRoles.READER_ID);

        assertThat(service.supervisedTargets("sub-" + manager, "catalog"))
                .containsExactlyInAnyOrder(t1, t2, t3);
    }

    @Test // U9 — two reports CONTROL-capable on the same team contribute the target ONCE
    void sharedTeamContributesTheTargetOnce() {
        UUID reportA = id();
        UUID reportB = id();
        UUID manager = managerOf(reportA);
        edge(manager, reportB);

        UUID target = UUID.randomUUID();
        UUID teamId = team("catalog", target);
        systemRole(SystemRoles.OWNER_ID, SystemRoles.OWNER);
        member(reportA, teamId, SystemRoles.OWNER_ID);
        member(reportB, teamId, SystemRoles.OWNER_ID);

        assertThat(service.supervisedTargets("sub-" + manager, "catalog")).containsExactly(target);
    }

    @Test // U10 — a CONTROL-capable seat on a team governing another TYPE contributes no ids
    void teamOfAnotherTargetTypeContributesNothing() {
        UUID report = id();
        UUID manager = managerOf(report);
        systemRole(SystemRoles.OWNER_ID, SystemRoles.OWNER);
        member(report, team("product", UUID.randomUUID()), SystemRoles.OWNER_ID);

        assertThat(service.supervisedTargets("sub-" + manager, "catalog")).isEmpty();
    }

    @Test // an unknown subject is the authoritative "supervises nothing" — never an error
    void unknownSubjectSupervisesNothing() {
        when(users.findBySubject("sub-nobody")).thenReturn(Optional.empty());
        assertThat(service.supervisedTargets("sub-nobody", "catalog")).isEmpty();
    }

    @Test // a breach in the closure zeroes the targets too — never a partial supervised set
    void closureBreachYieldsNoTargets() {
        UUID manager = chain(11);
        String subject = "sub-capped";
        when(users.findBySubject(subject))
                .thenReturn(Optional.of(new User(manager, subject, "Manager")));

        assertThat(service.supervisedTargets(subject, "catalog")).isEmpty();
    }

    @Test // a membership whose role row cannot be resolved is DROPPED, never defaulted to capable
    void unresolvableRoleContributesNothing() {
        UUID report = id();
        UUID manager = managerOf(report);
        UUID danglingRole = id();
        when(roles.findById(danglingRole)).thenReturn(Optional.empty());
        member(report, team("catalog", UUID.randomUUID()), danglingRole);

        assertThat(service.supervisedTargets("sub-" + manager, "catalog")).isEmpty();
    }
}
