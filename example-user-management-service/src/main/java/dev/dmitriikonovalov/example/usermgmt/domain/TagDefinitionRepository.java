package dev.dmitriikonovalov.example.usermgmt.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagDefinitionRepository extends JpaRepository<TagDefinition, UUID> {

    /** All global (system-wide) keys — {@code team_id IS NULL}. */
    List<TagDefinition> findByTeamIdIsNull();

    /** A global key by its name (globals have {@code teamId == null}). */
    Optional<TagDefinition> findByTeamIdIsNullAndKey(String key);

    /** A single team's keys. */
    List<TagDefinition> findByTeamId(UUID teamId);

    /** A team's key by name. */
    Optional<TagDefinition> findByTeamIdAndKey(UUID teamId, String key);

    /** Global keys plus a single team's keys — the applicable dictionary for that team's resources. */
    List<TagDefinition> findByTeamIdIsNullOrTeamId(UUID teamId);
}
