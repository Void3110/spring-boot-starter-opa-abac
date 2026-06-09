package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.openapi.api.MembershipApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.AddMemberRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.ChangeRoleRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.Membership;
import dev.dmitriikonovalov.example.usermgmt.service.CallerIdentity;
import dev.dmitriikonovalov.example.usermgmt.service.MembershipService;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Team-membership management — the service <strong>dogfooding</strong> the starter. Each mutating
 * endpoint is {@code @OpaPreAuthorize(action="team:manage", resourceType="'team'", resourceId="#teamId")}:
 * the library resolves the <em>calling subject's</em> role on this team (via the user-service's own
 * {@code RoleDefinitionSupplier}) and OPA's {@code team.rego} grants manage for owner/administrator
 * only. The decision authorizes the <b>actor</b>, never the service identity.
 *
 * <p>The orthogonal no-self-escalation subset rule lives in {@link MembershipService} (a manager still
 * cannot assign a role exceeding their own permissions). Controllers stay thin and delegate.
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
    @OpaPreAuthorize(action = "team:manage", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<List<Membership>> listMembers(UUID teamId) {
        var result = membershipService.list(teamId).stream()
                .map(v -> UserMgmtMapper.toDto(v.membership(), v.roleCode()))
                .toList();
        return ResponseEntity.ok(result);
    }

    @Override
    @OpaPreAuthorize(action = "team:manage", resourceType = "'team'", resourceId = "#teamId")
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
    @OpaPreAuthorize(action = "team:manage", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<Membership> changeMemberRole(
            UUID teamId, UUID userId, ChangeRoleRequest request) {
        UUID actor = callerIdentity.requireActingUserId(request.getActorUserId());
        var view = membershipService.changeRole(actor, teamId, userId, request.getRoleCode());
        return ResponseEntity.ok(UserMgmtMapper.toDto(view.membership(), view.roleCode()));
    }

    @Override
    @OpaPreAuthorize(action = "team:manage", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<Void> removeMember(UUID teamId, UUID userId) {
        membershipService.removeMember(teamId, userId);
        return ResponseEntity.noContent().build();
    }
}
