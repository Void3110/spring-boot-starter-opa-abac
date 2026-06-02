package dev.dmitriikonovalov.example.usermgmt.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleDefinitionRepository extends JpaRepository<RoleDefinitionEntity, UUID> {

    /** A system role by its stable code (system roles have {@code teamId == null}). */
    Optional<RoleDefinitionEntity> findBySystemTrueAndCode(String code);

    List<RoleDefinitionEntity> findBySystemTrue();

    /** System roles plus a single team's custom roles — the team's full role list (ticket 5/7). */
    List<RoleDefinitionEntity> findBySystemTrueOrTeamId(UUID teamId);

    List<RoleDefinitionEntity> findByTeamId(UUID teamId);

    Optional<RoleDefinitionEntity> findByTeamIdAndCode(UUID teamId, String code);
}
