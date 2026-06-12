package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.openapi.api.RoleDefinitionApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinition;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionPage;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionUpdate;
import dev.dmitriikonovalov.example.usermgmt.service.RoleDefinitionService;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Team-scoped custom role-definition management — <strong>owner only</strong>, dogfooding the starter.
 * Each endpoint is {@code @OpaPreAuthorize(action="team:define-roles", resourceType="'team'",
 * resourceId="#teamId")}; only the owner's resolved management ladder carries the {@code define-roles}
 * verb, so administrators (who can manage members) cannot define roles — matching the system-role table
 * in {@code 00-DESIGN.md}.
 *
 * <p>The Phase-6.5 authoring contract (level ceiling, category tokens, strict denials) and the
 * system-role-immutability rule live in {@link RoleDefinitionService}. Controllers stay thin.
 */
@RestController
public class RoleDefinitionController implements RoleDefinitionApi {

    private final RoleDefinitionService roleDefinitions;

    public RoleDefinitionController(RoleDefinitionService roleDefinitions) {
        this.roleDefinitions = roleDefinitions;
    }

    @Override
    @OpaPreAuthorize(action = "team:define-roles", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<RoleDefinitionPage> listRoleDefinitions(
            UUID teamId, Integer page, Integer perPage) {
        var result = roleDefinitions.list(teamId, PageDefaults.pageRequest(page, perPage));
        return ResponseEntity.ok(UserMgmtMapper.toRoleDefinitionPage(result));
    }

    @Override
    @OpaPreAuthorize(action = "team:define-roles", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<RoleDefinition> createRoleDefinition(
            UUID teamId, RoleDefinitionRequest request) {
        var created = roleDefinitions.create(
                teamId, request.getCode(), request.getRoleLevel(), request.getAttributes(),
                request.getPermissions(), request.getDeniedActions(),
                request.getRequiredTags(), matchModeOf(request.getMatchMode()));
        var dto = UserMgmtMapper.toDto(created);
        // A role definition is addressed by its code (GET /teams/{teamId}/role-definitions/{code}).
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{code}")
                .buildAndExpand(dto.getCode())
                .toUri();
        return ResponseEntity.created(location).body(dto);
    }

    @Override
    @OpaPreAuthorize(action = "team:define-roles", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<RoleDefinition> updateRoleDefinition(
            UUID teamId, String code, RoleDefinitionUpdate request) {
        var updated = roleDefinitions.update(
                teamId, code, request.getRoleLevel(), request.getAttributes(),
                request.getPermissions(), request.getDeniedActions(),
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
}
