package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.openapi.api.MembershipApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.AddMemberRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.ChangeRoleRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.Membership;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.MembershipPage;
import dev.dmitriikonovalov.example.usermgmt.service.CallerIdentity;
import dev.dmitriikonovalov.example.usermgmt.service.MembershipService;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Team-membership management — the service <strong>dogfooding</strong> the starter. Since Phase 6.7
 * (ADR 0015) each endpoint gates on its own <em>fine</em> action rather than the retired coarse
 * {@code team:manage}: {@code team:list-members} (now {@code READ} — any member can see the roster),
 * {@code team:add-member} / {@code team:change-role} / {@code team:remove-member} (the {@code CONTROL}
 * category). The library resolves the <em>calling subject's</em> role on this team (via the
 * user-service's own {@code RoleDefinitionSupplier}); OPA's {@code team.rego} expands the resolved
 * category tokens through the shared table and decides. The decision authorizes the <b>actor</b>, never
 * the service identity.
 *
 * <p>The orthogonal escalation bounds live in {@link MembershipService}: the hybrid assignment gates
 * (cross-tier + the senior subset verdict) on what may be <em>granted</em>, and the target-tier gate
 * on whom an existing member may be <em>demoted or removed by</em>. These are an untouched invariant
 * (the two-axis principle): categorizing the verbs changes which kinds of acts a role may perform, never
 * on whom or to what tier. Controllers stay thin and delegate.
 */
@RestController
public class MembershipController implements MembershipApi {

    private final MembershipService membershipService;
    private final CallerIdentity callerIdentity;

    public MembershipController(MembershipService membershipService, CallerIdentity callerIdentity) {
        this.membershipService = membershipService;
        this.callerIdentity = callerIdentity;
    }

    @Override
    @OpaPreAuthorize(action = "team:list-members", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<MembershipPage> listMembers(
            UUID teamId, Integer page, Integer perPage) {
        var result = membershipService.list(teamId, PageDefaults.pageRequest(page, perPage));
        return ResponseEntity.ok(UserMgmtMapper.toMembershipPage(result));
    }

    @Override
    @OpaPreAuthorize(action = "team:add-member", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<Membership> addMember(UUID teamId, AddMemberRequest request) {
        UUID actor = callerIdentity.requireActingUserId(request.getActorUserId());
        var view = membershipService.addMember(
                actor, teamId, request.getUserId(), request.getRoleCode());
        var dto = UserMgmtMapper.toDto(view.membership(), view.roleCode());
        // A membership is addressed by its member's userId (GET /teams/{teamId}/members/{userId}).
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{userId}")
                .buildAndExpand(dto.getUserId())
                .toUri();
        return ResponseEntity.created(location).body(dto);
    }

    @Override
    @OpaPreAuthorize(action = "team:change-role", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<Membership> changeMemberRole(
            UUID teamId, UUID userId, ChangeRoleRequest request) {
        UUID actor = callerIdentity.requireActingUserId(request.getActorUserId());
        var view = membershipService.changeRole(actor, teamId, userId, request.getRoleCode());
        return ResponseEntity.ok(UserMgmtMapper.toDto(view.membership(), view.roleCode()));
    }

    @Override
    @OpaPreAuthorize(action = "team:remove-member", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<Void> removeMember(UUID teamId, UUID userId) {
        // DELETE carries no body, so the actor is the authenticated subject only — needed for the
        // target-tier gate (a senior must not remove an administrator).
        UUID actor = callerIdentity.requireActingUserId(null);
        membershipService.removeMember(actor, teamId, userId);
        return ResponseEntity.noContent().build();
    }
}
