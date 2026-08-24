package dev.dmitriikonovalov.example.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * U3 (Phase 6.7) — the management projection {@code managementRole} keeps its shape
 * ({@code Map.of("team", forCode(code))}) but now emits the coarse <b>category tokens</b>
 * {@code team.rego} expands, not retired fine verbs. Mock-driven so it stays a unit test (no Postgres).
 */
class EffectiveRoleServiceManagementRoleTest {

    private final TeamMembershipRepository memberships = mock(TeamMembershipRepository.class);
    private final RoleDefinitionRepository roles = mock(RoleDefinitionRepository.class);
    private final EffectiveRoleService service = new EffectiveRoleService(
            memberships,
            roles,
            mock(TeamRepository.class),
            mock(UserRepository.class),
            mock(TeamTargetMatcher.class),
            mock(SupervisionService.class));

    private static final UUID TEAM = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private void boundTo(String code, UUID roleId) {
        TeamMembership m = new TeamMembership(UUID.randomUUID(), TEAM, USER, roleId);
        when(memberships.findByTeamIdAndUserId(TEAM, USER)).thenReturn(Optional.of(m));
        RoleDefinitionEntity role = new RoleDefinitionEntity(
                roleId, code, true, null, Map.of("role_level", 30), Map.of());
        when(roles.findById(roleId)).thenReturn(Optional.of(role));
    }

    @Test // U3 — an administrator membership projects permissions["team"] to the category tokens
    void administratorManagementRoleEmitsCategoryTokens() {
        UUID roleId = SystemRoles.ADMINISTRATOR_ID;
        boundTo(SystemRoles.ADMINISTRATOR, roleId);

        Optional<RoleDefinition> resolved = service.managementRole(TEAM, USER);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().permissions())
                .containsOnlyKeys("team")
                .extractingByKey("team", org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly("READ", "CONTROL", "TAG");
    }

    @Test // a custom code projects to READ only — management-incapable (the I12 default)
    void customManagementRoleEmitsReadOnly() {
        UUID roleId = UUID.randomUUID();
        boundTo("lead", roleId);

        Optional<RoleDefinition> resolved = service.managementRole(TEAM, USER);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().permissions().get("team")).containsExactly("READ");
    }

    @Test // no membership → empty (the dogfooded policy default-denies)
    void noMembershipIsEmpty() {
        when(memberships.findByTeamIdAndUserId(TEAM, USER)).thenReturn(Optional.empty());
        assertThat(service.managementRole(TEAM, USER)).isEmpty();
    }

    @Test // deep review 2026-08-24: a present-null "*" value WAS storable before the write
    // path's null rejection (round 4) — legacy rows may still hold it, and the resolve wire
    // must narrow (expand nothing), never NPE (Map.of rejected the null before the guard).
    void presentNullWildcardValueResolvesWithoutExpandingOrThrowing() {
        UUID roleId = UUID.randomUUID();
        TeamMembership m = new TeamMembership(UUID.randomUUID(), TEAM, USER, roleId);
        Map<String, List<String>> nullStarGrants = new HashMap<>();
        nullStarGrants.put("*", null);
        Map<String, List<String>> nullStarDenials = new HashMap<>();
        nullStarDenials.put("*", null);
        RoleDefinitionEntity role = new RoleDefinitionEntity(
                roleId, "corrupt-role", false, TEAM, Map.of("role_level", 20), nullStarGrants);
        role.setDeniedActions(nullStarDenials);
        when(roles.findById(roleId)).thenReturn(Optional.of(role));

        RoleDefinition resolved = service.resourceRole(m, "catalog");

        assertThat(resolved.permissions()).containsOnlyKeys("*");
        assertThat(resolved.deniedActions()).containsOnlyKeys("*");
    }
}
