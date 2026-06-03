package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.User;

/**
 * Maps JPA entities to the generated OpenAPI DTOs — hand-written, mirroring the catalog app's
 * {@code CatalogMapper} (deliberately no MapStruct; the example services stay consistent and the
 * flat DTOs don't justify a third codegen stage). One static {@code toDto} per entity.
 */
public final class UserMgmtMapper {

    private UserMgmtMapper() {
    }

    public static dev.dmitriikonovalov.example.usermgmt.openapi.model.User toDto(User e) {
        return new dev.dmitriikonovalov.example.usermgmt.openapi.model.User()
                .id(e.getId())
                .subject(e.getSubject())
                .displayName(e.getDisplayName());
    }

    public static dev.dmitriikonovalov.example.usermgmt.openapi.model.Team toDto(Team e) {
        return new dev.dmitriikonovalov.example.usermgmt.openapi.model.Team()
                .id(e.getId())
                .name(e.getName())
                .targetType(e.getTargetType())
                .targetId(e.getTargetId());
    }

    /**
     * A membership carries a {@code roleDefinitionId}; the DTO exposes the human-readable
     * {@code roleCode}, so the caller resolves the bound role's code and passes it in.
     */
    public static dev.dmitriikonovalov.example.usermgmt.openapi.model.Membership toDto(
            TeamMembership e, String roleCode) {
        return new dev.dmitriikonovalov.example.usermgmt.openapi.model.Membership()
                .id(e.getId())
                .teamId(e.getTeamId())
                .userId(e.getUserId())
                .roleCode(roleCode);
    }

    public static dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinition toDto(
            RoleDefinitionEntity e) {
        return new dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinition()
                .code(e.getCode())
                .system(e.isSystem())
                .teamId(e.getTeamId())
                .attributes(e.getAttributes())
                .permissions(e.getPermissions());
    }

    public static dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition toDto(
            TagDefinition e) {
        return new dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition()
                .id(e.getId())
                .key(e.getKey())
                .scope(dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition.ScopeEnum.valueOf(
                        e.getScope().name()))
                .teamId(e.getTeamId())
                .valueType(
                        dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition.ValueTypeEnum
                                .valueOf(e.getValueType().name()))
                .cardinality(
                        dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition.CardinalityEnum
                                .valueOf(e.getCardinality().name()))
                .allowedValues(e.getAllowedValues())
                .valuePattern(e.getValuePattern())
                .system(e.isSystem());
    }
}
