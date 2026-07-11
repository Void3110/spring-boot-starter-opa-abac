package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.AddMemberRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.ChangeRoleRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.Membership;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The hybrid assignment-gate matrix (Phase 6.5 — QA I6–I10, I12). The in-process
 * {@code com.sun.net.httpserver.HttpServer} stub plays {@code /v1/data/role/assignable} with
 * <b>programmable verdicts</b> (true / false / 500 / missing-result) and counts how often it was
 * consulted — the REAL subset math is {@code opa test}'s job (P13); these cells pin the Java gate
 * order, the fail-closed client, and the wire contract ({@code 422 ROLE_SUBSET_VIOLATION}).
 */
class MembershipGateIT extends AbstractSecuredPostgresIT {

    /** Programmable stub behaviors. {@code TIMEOUT} answers true — but only after the client gave up. */
    private enum Verdict { TRUE, FALSE, SERVER_ERROR, MISSING_RESULT, TIMEOUT }

    private static final HttpServer OPA_STUB;
    private static volatile Supplier<Verdict> verdictSupplier = () -> Verdict.FALSE;
    private static final AtomicInteger assignableCalls = new AtomicInteger();

    static {
        try {
            OPA_STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        OPA_STUB.createContext("/v1/data/role/assignable", exchange -> {
            assignableCalls.incrementAndGet();
            Verdict verdict = verdictSupplier.get();
            if (verdict == Verdict.TIMEOUT) {
                // Stall past the client's 2s read timeout, then answer a POSITIVE verdict — the
                // client must already have failed closed; the late "true" must change nothing.
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String body = switch (verdict) {
                case TRUE, TIMEOUT -> "{\"result\": true}";
                case FALSE -> "{\"result\": false}";
                case SERVER_ERROR -> "boom";
                case MISSING_RESULT -> "{}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(verdict == Verdict.SERVER_ERROR ? 500 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        OPA_STUB.start();
    }

    @DynamicPropertySource
    static void opaStubProps(DynamicPropertyRegistry registry) {
        registry.add("opa.abac.base-url", () -> "http://127.0.0.1:" + OPA_STUB.getAddress().getPort());
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;
    @Autowired private RoleDefinitionRepository roles;

    @BeforeEach
    void resetStub() {
        verdictSupplier = () -> Verdict.FALSE;
        assignableCalls.set(0);
    }

    private User user(String name) {
        return users.save(new User(UUID.randomUUID(), "sub-" + name + "-" + UUID.randomUUID(), name));
    }

    private Team team() {
        return teams.save(new Team(UUID.randomUUID(), "Gates", "catalog", UUID.randomUUID()));
    }

    private void grant(Team team, User user, UUID roleId) {
        memberships.save(new TeamMembership(UUID.randomUUID(), team.getId(), user.getId(), roleId));
    }

    private org.springframework.http.ResponseEntity<String> addMemberAs(
            User actor, Team team, User target, String roleCode) {
        return rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.POST,
                AbacTestConfig.as(
                        actor.getSubject(),
                        new AddMemberRequest().userId(target.getId()).roleCode(roleCode)),
                String.class,
                team.getId());
    }

    // --- I6: senior assigns member-level; assignable -> true → 201 -----------------

    @Test
    void seniorAssignsMemberLevelWithPositiveVerdict() {
        verdictSupplier = () -> Verdict.TRUE;
        Team team = team();
        User senior = user("senior");
        User target = user("target");
        grant(team, senior, SystemRoles.SENIOR_ID);

        var add = rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.POST,
                AbacTestConfig.as(
                        senior.getSubject(),
                        new AddMemberRequest().userId(target.getId()).roleCode(SystemRoles.MEMBER)),
                Membership.class,
                team.getId());
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(assignableCalls.get()).isEqualTo(1);
    }

    // --- I7: senior assigns senior/admin level → 422; assignable NOT consulted -------

    @Test
    void seniorCannotAssignAtOrAboveItsTier() {
        verdictSupplier = () -> Verdict.TRUE; // even a positive verdict must not matter
        Team team = team();
        User senior = user("senior");
        grant(team, senior, SystemRoles.SENIOR_ID);

        for (String code : List.of(SystemRoles.SENIOR, SystemRoles.ADMINISTRATOR)) {
            User target = user("target-" + code);
            var add = addMemberAs(senior, team, target, code);
            assertThat(add.getStatusCode()).as(code).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(add.getBody()).as(code).contains("ROLE_SUBSET_VIOLATION");
        }
        // The level gate fired first — OPA was never asked.
        assertThat(assignableCalls.get()).isZero();
    }

    // --- I8: senior assigns member-level; assignable -> false → 422 -------------------

    @Test
    void seniorRejectedByNegativeVerdict() {
        verdictSupplier = () -> Verdict.FALSE;
        Team team = team();
        User senior = user("senior");
        User target = user("target");
        grant(team, senior, SystemRoles.SENIOR_ID);

        var add = addMemberAs(senior, team, target, SystemRoles.MEMBER);
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(add.getBody()).contains("ROLE_SUBSET_VIOLATION");
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), target.getId())).isEmpty();
    }

    // --- I9: OPA error / missing result → 422, fail-closed, no row written --------------

    @Test
    void seniorRejectedWhenOpaFails() {
        Team team = team();
        User senior = user("senior");
        grant(team, senior, SystemRoles.SENIOR_ID);

        for (Verdict failure : List.of(Verdict.SERVER_ERROR, Verdict.MISSING_RESULT)) {
            verdictSupplier = () -> failure;
            User target = user("target-" + failure);
            var add = addMemberAs(senior, team, target, SystemRoles.MEMBER);
            assertThat(add.getStatusCode()).as(failure.name()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(add.getBody()).as(failure.name()).contains("ROLE_SUBSET_VIOLATION");
            assertThat(memberships.findByTeamIdAndUserId(team.getId(), target.getId()))
                    .as(failure.name()).isEmpty();
        }
    }

    @Test // I9's third leg (review fix, 2026-06-12): the verdict call TIMES OUT → fail-closed 422,
    // even though the stub eventually answers a positive verdict.
    void seniorRejectedWhenOpaTimesOut() {
        verdictSupplier = () -> Verdict.TIMEOUT;
        Team team = team();
        User senior = user("senior");
        User target = user("target");
        grant(team, senior, SystemRoles.SENIOR_ID);

        var add = addMemberAs(senior, team, target, SystemRoles.MEMBER);
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(add.getBody()).contains("ROLE_SUBSET_VIOLATION");
        assertThat(assignableCalls.get()).isEqualTo(1);
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), target.getId())).isEmpty();
    }

    // --- I10: the admin tier — strict <, no OPA consult, the designed denial cell ---------

    @Test
    void adminAssignsBelowButNeverPeers() {
        Team team = team();
        User admin = user("admin");
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);

        // below: member (20) → 201
        User below = user("below");
        assertThat(addMemberAs(admin, team, below, SystemRoles.MEMBER).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // peer admin (30) → 422 (strict <)
        User peer = user("peer");
        var mint = addMemberAs(admin, team, peer, SystemRoles.ADMINISTRATOR);
        assertThat(mint.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(mint.getBody()).contains("ROLE_SUBSET_VIOLATION");

        // assignable is never consulted on the admin path.
        assertThat(assignableCalls.get()).isZero();
    }

    @Autowired private dev.dmitriikonovalov.example.usermgmt.service.MembershipService membershipService;

    @Test // the DESIGNED cell: an admin-TIER actor whose own role denies delete still assigns full WRITE
    void adminWithDenialAssignsFullWrite() {
        // Service-level on purpose: the GATE PAIR is the subject here. A custom level-30 code
        // carries no team:manage capability (the I12 pin), so the HTTP path would 403 before the
        // gates run — admin-tier-with-denial is only constructible on a custom row.
        Team team = team();
        User admin = user("denied-admin");
        RoleDefinitionEntity noDeleteAdmin = new RoleDefinitionEntity(
                UUID.randomUUID(),
                "no-delete-admin",
                false,
                team.getId(),
                Map.of("role_level", 30),
                Map.of("*", List.of("READ", "WRITE", "TAG", "GRANT")));
        noDeleteAdmin.setDeniedActions(Map.of("*", List.of("delete")));
        roles.save(noDeleteAdmin);
        grant(team, admin, noDeleteAdmin.getId());

        RoleDefinitionEntity fullWrite = new RoleDefinitionEntity(
                UUID.randomUUID(),
                "full-writer",
                false,
                team.getId(),
                Map.of("role_level", 20),
                Map.of("catalog", List.of("READ", "WRITE")));
        roles.save(fullWrite);

        User target = user("target");
        var view = membershipService.addMember(
                admin.getId(), team.getId(), target.getId(), "full-writer");
        // Admin power comes from the TIER, not the effective set — no subset verdict applies at 30.
        assertThat(view.roleCode()).isEqualTo("full-writer");
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), target.getId())).isPresent();
        assertThat(assignableCalls.get()).isZero();
    }

    // --- I12: a custom level-25 role has senior's ceiling but NO live assign power ---------

    @Test
    void customLevel25RoleCannotManage() {
        Team team = team();
        User pseudoSenior = user("pseudo-senior");
        RoleDefinitionEntity custom25 = roles.save(new RoleDefinitionEntity(
                UUID.randomUUID(),
                "custom-25",
                false,
                team.getId(),
                Map.of("role_level", 25),
                Map.of("catalog", List.of("READ", "WRITE", "TAG"))));
        grant(team, pseudoSenior, custom25.getId());

        User target = user("target");
        var add = addMemberAs(pseudoSenior, team, target, SystemRoles.READER);
        // 403 at the team:manage gate — TeamRoleCapabilities gives custom codes no manage verb.
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(assignableCalls.get()).isZero();
    }

    // --- The target-tier gate (review fix, 2026-06-12): managing is bounded by the TARGET's ---
    // --- current tier too — a senior must not demote or remove an administrator. -------------

    @Test
    void seniorCannotDemoteAnAdministrator() {
        verdictSupplier = () -> Verdict.TRUE; // even a positive subset verdict must not matter
        Team team = team();
        User senior = user("senior");
        User admin = user("admin");
        grant(team, senior, SystemRoles.SENIOR_ID);
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);

        var demote = rest.exchange(
                "/api/v1/teams/{t}/members/{u}",
                HttpMethod.PUT,
                AbacTestConfig.as(senior.getSubject(), new ChangeRoleRequest().roleCode(SystemRoles.READER)),
                String.class,
                team.getId(),
                admin.getId());
        assertThat(demote.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(demote.getBody()).contains("ROLE_SUBSET_VIOLATION");
        // The target gate fired before the candidate gates — OPA was never consulted.
        assertThat(assignableCalls.get()).isZero();
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), admin.getId()))
                .get()
                .extracting(TeamMembership::getRoleDefinitionId)
                .isEqualTo(SystemRoles.ADMINISTRATOR_ID);
    }

    @Test
    void seniorCannotRemoveAnAdministrator() {
        Team team = team();
        User senior = user("senior");
        User admin = user("admin");
        grant(team, senior, SystemRoles.SENIOR_ID);
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);

        var remove = rest.exchange(
                "/api/v1/teams/{t}/members/{u}",
                HttpMethod.DELETE,
                AbacTestConfig.as(senior.getSubject()),
                String.class,
                team.getId(),
                admin.getId());
        assertThat(remove.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(remove.getBody()).contains("ROLE_SUBSET_VIOLATION");
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), admin.getId())).isPresent();
    }

    @Test // peers stay manageable — the pre-6.5 cell, unchanged by the target gate
    void adminRemovesAPeerAdministrator() {
        Team team = team();
        User admin = user("admin");
        User peer = user("peer-admin");
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);
        grant(team, peer, SystemRoles.ADMINISTRATOR_ID);

        var remove = rest.exchange(
                "/api/v1/teams/{t}/members/{u}",
                HttpMethod.DELETE,
                AbacTestConfig.as(admin.getSubject()),
                Void.class,
                team.getId(),
                peer.getId());
        assertThat(remove.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), peer.getId())).isEmpty();
    }

    @Test // the senior's designed power is intact: members at/below their tier stay manageable
    void seniorRemovesAMember() {
        Team team = team();
        User senior = user("senior");
        User member = user("member");
        grant(team, senior, SystemRoles.SENIOR_ID);
        grant(team, member, SystemRoles.MEMBER_ID);

        var remove = rest.exchange(
                "/api/v1/teams/{t}/members/{u}",
                HttpMethod.DELETE,
                AbacTestConfig.as(senior.getSubject()),
                Void.class,
                team.getId(),
                member.getId());
        assertThat(remove.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), member.getId())).isEmpty();
    }
}
