package dev.dmitriikonovalov.example.usermgmt.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The legacy-row normalization on the display DTO path (deep review 2026-08-24, round 7 pin):
 * rows written before the null-value 422 rejection may carry null map values, and the documented
 * schema says every value is an array — {@code toDto} must serialize them as empty lists.
 */
class UserMgmtMapperRoleDefinitionTest {

    @Test
    void legacyNullMapValuesSerializeAsEmptyLists() {
        Map<String, List<String>> permissions = new HashMap<>();
        permissions.put("catalog", List.of("READ"));
        permissions.put("category", null);
        Map<String, List<String>> denied = new HashMap<>();
        denied.put("*", null);
        RoleDefinitionEntity e = new RoleDefinitionEntity(
                UUID.randomUUID(), "legacy-role", false, UUID.randomUUID(),
                Map.of("role_level", 20), permissions);
        e.setDeniedActions(denied);

        var dto = UserMgmtMapper.toDto(e);

        assertThat(dto.getPermissions())
                .containsEntry("catalog", List.of("READ"))
                .containsEntry("category", List.of());
        assertThat(dto.getDeniedActions()).containsEntry("*", List.of());
    }
}
