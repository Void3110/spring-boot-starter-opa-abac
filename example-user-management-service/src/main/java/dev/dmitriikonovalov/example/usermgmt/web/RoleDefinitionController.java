package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.openapi.api.RoleDefinitionApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinition;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionUpdate;
import dev.dmitriikonovalov.example.usermgmt.service.CallerIdentity;
import dev.dmitriikonovalov.example.usermgmt.service.RoleDefinitionService;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Team-scoped custom role-definition management — <strong>owner only</strong>, dogfooding the starter.
 * Each endpoint is {@code @OpaPreAuthorize(action="team:define-roles", resourceType="'team'",
 * resourceId="#teamId")}; only the owner's resolved management ladder carries the {@code define-roles}
 * verb, so administrators (who can manage members) cannot define roles — matching the system-role table
 * in {@code 00-DESIGN.md}.
 *
 * <p>The orthogonal subset-of-own guard and the system-role-immutability rule live in
 * {@link RoleDefinitionService}. Controllers stay thin.
 */
@RestController
public class RoleDefinitionController implements RoleDefinitionApi {

    private final RoleDefinitionService roleDefinitions;
    private final CallerIdentity callerIdentity;

    public RoleDefinitionController(
            RoleDefinitionService roleDefinitions, CallerIdentity callerIdentity) {
        this.roleDefinitions = roleDefinitions;
        this.callerIdentity = callerIdentity;
    }

    @Override
    @OpaPreAuthorize(action = "team:define-roles", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<List<RoleDefinition>> listRoleDefinitions(UUID teamId) {
        var result = roleDefinitions.list(teamId).stream().map(UserMgmtMapper::toDto).toList();
        return ResponseEntity.ok(result);
    }

    @Override
    @OpaPreAuthorize(action = "team:define-roles", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<RoleDefinition> createRoleDefinition(
            UUID teamId, RoleDefinitionRequest request) {
        UUID actor = actor();
        var created = roleDefinitions.create(
                actor, teamId, request.getCode(), request.getAttributes(), request.getPermissions(),
                request.getRequiredTags(), matchModeOf(request.getMatchMode()));
        return ResponseEntity.status(HttpStatus.CREATED).body(UserMgmtMapper.toDto(created));
    }

    @Override
    @OpaPreAuthorize(action = "team:define-roles", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<RoleDefinition> updateRoleDefinition(
            UUID teamId, String code, RoleDefinitionUpdate request) {
        UUID actor = actor();
        var updated = roleDefinitions.update(
                actor, teamId, code, request.getAttributes(), request.getPermissions(),
                request.getRequiredTags(), matchModeOf(request.getMatchMode()));
        return ResponseEntity.ok(UserMgmtMapper.toDto(updated));
    }

    private static String matchModeOf(RoleDefinitionRequest.MatchModeEnum e) {
        return e == null ? null : e.getValue();
    }

    private static String matchModeOf(RoleDefinitionUpdate.MatchModeEnum e) {
        return e == null ? null : e.getValue();
    }

    @Override
    @OpaPreAuthorize(action = "team:define-roles", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<Void> deleteRoleDefinition(UUID teamId, String code) {
        roleDefinitions.delete(teamId, code);
        return ResponseEntity.noContent().build();
    }

    private UUID actor() {
        return callerIdentity.currentUserId()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No acting user: the request subject does not map to a known user"));
    }
}
