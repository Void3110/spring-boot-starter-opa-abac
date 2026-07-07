package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.MembershipPage;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinitionPage;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinitionPage;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TeamPage;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.UserPage;
import dev.dmitriikonovalov.example.usermgmt.service.MembershipView;
import org.springframework.data.domain.Page;

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

    /**
     * The directory rows are the library's type-bounded {@code DirectoryUser} (subject + displayName —
     * the disclosure ceiling); {@code limit} echoes the effective (clamped) value the search ran with.
     */
    public static dev.dmitriikonovalov.example.usermgmt.openapi.model.DirectoryUserList toDirectoryUserList(
            java.util.List<dev.dmitriikonovalov.opaabac.security.directory.DirectoryUser> matches, int limit) {
        var list = new dev.dmitriikonovalov.example.usermgmt.openapi.model.DirectoryUserList()
                .items(new java.util.ArrayList<>())
                .limit(limit);
        matches.forEach(m -> list.addItemsItem(
                new dev.dmitriikonovalov.example.usermgmt.openapi.model.DirectoryUser()
                        .subject(m.subject())
                        .displayName(m.displayName())));
        return list;
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
        var dto = new dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinition()
                .code(e.getCode())
                .system(e.isSystem())
                .teamId(e.getTeamId())
                .roleLevel(roleLevelOf(e))
                .attributes(e.getAttributes())
                .permissions(e.getPermissions())
                .deniedActions(e.getDeniedActions())
                .requiredTags(e.getRequiredTags());
        if (e.getMatchMode() != null) {
            dto.matchMode(
                    dev.dmitriikonovalov.example.usermgmt.openapi.model.RoleDefinition.MatchModeEnum
                            .valueOf(e.getMatchMode()));
        }
        return dto;
    }

    /** The G1 round-trip lens: roleLevel is read from {@code attributes.role_level} (null if unreadable). */
    private static Integer roleLevelOf(RoleDefinitionEntity e) {
        Object level = e.getAttributes().get("role_level");
        return level instanceof Number n ? n.intValue() : null;
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

    // --- the list envelope (ADR 0012): count = the page's totalElements; page/perPage echo the request.

    public static UserPage toUserPage(Page<User> page) {
        return new UserPage()
                .count(page.getTotalElements())
                .page(page.getNumber())
                .perPage(page.getSize())
                .items(page.getContent().stream().map(UserMgmtMapper::toDto).toList());
    }

    public static TeamPage toTeamPage(Page<Team> page) {
        return new TeamPage()
                .count(page.getTotalElements())
                .page(page.getNumber())
                .perPage(page.getSize())
                .items(page.getContent().stream().map(UserMgmtMapper::toDto).toList());
    }

    public static MembershipPage toMembershipPage(Page<MembershipView> page) {
        return new MembershipPage()
                .count(page.getTotalElements())
                .page(page.getNumber())
                .perPage(page.getSize())
                .items(page.getContent().stream()
                        .map(v -> toDto(v.membership(), v.roleCode()))
                        .toList());
    }

    public static RoleDefinitionPage toRoleDefinitionPage(Page<RoleDefinitionEntity> page) {
        return new RoleDefinitionPage()
                .count(page.getTotalElements())
                .page(page.getNumber())
                .perPage(page.getSize())
                .items(page.getContent().stream().map(UserMgmtMapper::toDto).toList());
    }

    public static TagDefinitionPage toTagDefinitionPage(Page<TagDefinition> page) {
        return new TagDefinitionPage()
                .count(page.getTotalElements())
                .page(page.getNumber())
                .perPage(page.getSize())
                .items(page.getContent().stream().map(UserMgmtMapper::toDto).toList());
    }
}
