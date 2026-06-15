package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.AddMemberRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.ChangeRoleRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinitionRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TransferOwnershipRequest;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Phase 6.7 headline IT (QA I1–I6) — the categorized control plane through the <b>renamed</b> verbs,
 * end to end against real Postgres (Testcontainers) on the dogfooded {@code @OpaPreAuthorize} →
 * resolve → in-process {@code team.rego} mirror path. Proves the full behavior matrix (00-DESIGN §3)
 * and, crucially, that the <b>two intended changes are exactly what changed</b> and nothing wider:
 *
 * <ul>
 *   <li><b>I1/I2</b> — the loosening is exactly {@code list-members}: a READ-only role can SEE the
 *       roster but mutate nothing;</li>
 *   <li><b>I3</b> — {@code senior} manages members (CONTROL) but cannot {@code define-tags}
 *       (CONTROL-not-TAG);</li>
 *   <li><b>I4</b> — owner/administrator curate tags (both carry TAG);</li>
 *   <li><b>I5</b> — the owner-only fences: only owner reaches {@code define-roles} /
 *       {@code transfer-ownership};</li>
 *   <li><b>I6</b> — the two-axis re-proof: a renamed verb ({@code change-role}) still flows through the
 *       untouched {@code MembershipService} escalation gate (the rename did not bypass it).</li>
 * </ul>
 */
class ControlPlaneVocabularyIT extends AbstractSecuredPostgresIT {

    // A POSITIVE stub for data.role.assignable so the senior tier's subset verdict is satisfied — the
    // ESCALATION axis (MembershipService) is exhaustively proven in MembershipGateIT; here the verdict
    // is true so the senior-add cell (I3, the CONTROL-vs-TAG distinction) reaches its intended 201. The
    // I6 above-tier promotion is rejected by the cross-tier LEVEL gate before this stub is consulted.
    private static final HttpServer OPA_STUB;

    static {
        try {
            OPA_STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        OPA_STUB.createContext("/v1/data/role/assignable", exchange -> {
            byte[] body = "{\"result\": true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
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

    private User user(String name) {
        return users.save(new User(UUID.randomUUID(), "sub-" + name + "-" + UUID.randomUUID(), name));
    }

    private Team team() {
        return teams.save(new Team(UUID.randomUUID(), "Acme", "catalog", UUID.randomUUID()));
    }

    private void grant(Team team, User user, UUID roleId) {
        memberships.save(new TeamMembership(UUID.randomUUID(), team.getId(), user.getId(), roleId));
    }

    private org.springframework.http.ResponseEntity<String> get(String path, User as, Object... uri) {
        return rest.exchange(path, HttpMethod.GET, AbacTestConfig.as(as.getSubject()), String.class, uri);
    }

    private org.springframework.http.ResponseEntity<String> addMemberAs(
            User actor, Team team, User target, String roleCode) {
        return rest.exchange(
                "/api/v1/teams/{t}/members",
                HttpMethod.POST,
                AbacTestConfig.as(actor.getSubject(),
                        new AddMemberRequest().userId(target.getId()).roleCode(roleCode)),
                String.class,
                team.getId());
    }

    private org.springframework.http.ResponseEntity<String> defineTagAs(User actor, Team team) {
        return rest.exchange(
                "/api/v1/teams/{t}/tag-definitions",
                HttpMethod.POST,
                AbacTestConfig.as(actor.getSubject(), new TagDefinitionRequest()
                        .key("tier")
                        .valueType(TagDefinitionRequest.ValueTypeEnum.ENUM)
                        .cardinality(TagDefinitionRequest.CardinalityEnum.MULTI)
                        .allowedValues(List.of("gold", "silver"))),
                String.class,
                team.getId());
    }

    // --- I1 + I2: the loosening is EXACTLY list-members ---------------------------------

    @Test // I1 — a plain member can list the roster (was 403); I2 — but mutates nothing
    void memberListsRosterButCannotMutate() {
        Team team = team();
        User member = user("member");
        User other = user("other");
        grant(team, member, SystemRoles.MEMBER_ID);
        grant(team, other, SystemRoles.READER_ID);

        // I1: list-members rides READ -> 200
        assertThat(get("/api/v1/teams/{t}/members", member, team.getId()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // I2: every mutation + tag-write still denies (the loosening is exactly listing)
        assertThat(addMemberAs(member, team, user("newbie"), SystemRoles.READER).getStatusCode())
                .as("add-member").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange(
                        "/api/v1/teams/{t}/members/{u}",
                        HttpMethod.PUT,
                        AbacTestConfig.as(member.getSubject(),
                                new ChangeRoleRequest().roleCode(SystemRoles.READER)),
                        String.class, team.getId(), other.getId())
                .getStatusCode()).as("change-role").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange(
                        "/api/v1/teams/{t}/members/{u}",
                        HttpMethod.DELETE,
                        AbacTestConfig.as(member.getSubject()),
                        String.class, team.getId(), other.getId())
                .getStatusCode()).as("remove-member").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(defineTagAs(member, team).getStatusCode())
                .as("define-tags").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test // I2 — a reader likewise: lists the roster but is denied every mutation
    void readerListsRosterButCannotMutate() {
        Team team = team();
        User reader = user("reader");
        grant(team, reader, SystemRoles.READER_ID);

        assertThat(get("/api/v1/teams/{t}/members", reader, team.getId()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(addMemberAs(reader, team, user("x"), SystemRoles.READER).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(defineTagAs(reader, team).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- I3: senior manages members (CONTROL) but cannot define-tags (CONTROL-not-TAG) ---

    @Test
    void seniorManagesMembersButCannotDefineTags() {
        Team team = team();
        User senior = user("senior");
        User newbie = user("newbie");
        grant(team, senior, SystemRoles.SENIOR_ID);

        // add a member-tier user succeeds (CONTROL; below the senior tier — the gate permits it)
        var add = addMemberAs(senior, team, newbie, SystemRoles.READER);
        assertThat(add.getStatusCode()).as("senior add-member").isEqualTo(HttpStatus.CREATED);

        // but curating the tag dictionary is denied (senior holds CONTROL, not TAG)
        assertThat(defineTagAs(senior, team).getStatusCode())
                .as("senior define-tags").isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- I4: owner / administrator curate tags (both carry TAG) ---------------------------

    @Test
    void ownerAndAdministratorCurateTags() {
        Team teamA = team();
        User owner = user("owner");
        grant(teamA, owner, SystemRoles.OWNER_ID);
        assertThat(defineTagAs(owner, teamA).getStatusCode())
                .as("owner define-tags").isEqualTo(HttpStatus.CREATED);

        Team teamB = team();
        User admin = user("admin");
        grant(teamB, admin, SystemRoles.ADMINISTRATOR_ID);
        assertThat(defineTagAs(admin, teamB).getStatusCode())
                .as("administrator define-tags").isEqualTo(HttpStatus.CREATED);
    }

    // --- I5: the owner-only fences (define-roles / transfer-ownership) ---------------------

    @Test
    void onlyOwnerReachesTheOwnerOnlyFences() {
        Team team = team();
        User owner = user("owner");
        User admin = user("admin");
        grant(team, owner, SystemRoles.OWNER_ID);
        grant(team, admin, SystemRoles.ADMINISTRATOR_ID);

        // define-roles: owner authorized at the gate (200 list), administrator denied (403)
        assertThat(get("/api/v1/teams/{t}/role-definitions", owner, team.getId()).getStatusCode())
                .as("owner define-roles").isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/teams/{t}/role-definitions", admin, team.getId()).getStatusCode())
                .as("administrator define-roles").isEqualTo(HttpStatus.FORBIDDEN);

        // transfer-ownership: administrator denied at the gate (403, owner-only fence)
        var adminTransfer = rest.exchange(
                "/api/v1/teams/{t}/transfer-ownership",
                HttpMethod.POST,
                AbacTestConfig.as(admin.getSubject(),
                        new TransferOwnershipRequest().newOwnerUserId(admin.getId())),
                String.class,
                team.getId());
        assertThat(adminTransfer.getStatusCode())
                .as("administrator transfer-ownership").isEqualTo(HttpStatus.FORBIDDEN);

        // owner transfer to the admin succeeds (the fence grants the owner code)
        var ownerTransfer = rest.exchange(
                "/api/v1/teams/{t}/transfer-ownership",
                HttpMethod.POST,
                AbacTestConfig.as(owner.getSubject(),
                        new TransferOwnershipRequest().newOwnerUserId(admin.getId())),
                Void.class,
                team.getId());
        assertThat(ownerTransfer.getStatusCode())
                .as("owner transfer-ownership").isEqualTo(HttpStatus.NO_CONTENT);
    }

    // --- I6: the two-axis re-proof — a renamed verb still hits the untouched escalation gate ---

    @Test // a senior change-roles a member UP past its own tier (to administrator) -> 422
    void seniorChangeRoleUpPastTierStillHitsTheEscalationGate() {
        Team team = team();
        User senior = user("senior");
        User member = user("member");
        grant(team, senior, SystemRoles.SENIOR_ID);
        grant(team, member, SystemRoles.MEMBER_ID);

        var promote = rest.exchange(
                "/api/v1/teams/{t}/members/{u}",
                HttpMethod.PUT,
                AbacTestConfig.as(senior.getSubject(),
                        new ChangeRoleRequest().roleCode(SystemRoles.ADMINISTRATOR)),
                String.class,
                team.getId(),
                member.getId());

        // The renamed team:change-role verb authorized the senior at the policy gate (it holds CONTROL),
        // then the UNTOUCHED MembershipService cross-tier gate rejected the above-tier promotion —
        // proving the verb rename did not bypass the second (escalation) axis.
        assertThat(promote.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(promote.getBody()).contains("ROLE_SUBSET_VIOLATION");
        // unchanged: the member is still a member.
        assertThat(memberships.findByTeamIdAndUserId(team.getId(), member.getId()))
                .get()
                .extracting(TeamMembership::getRoleDefinitionId)
                .isEqualTo(SystemRoles.MEMBER_ID);
    }
}
