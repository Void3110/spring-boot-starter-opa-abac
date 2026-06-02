package dev.dmitriikonovalov.example.usermgmt.domain;

import dev.dmitriikonovalov.opaabac.data.repository.LockableJpaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository
        extends JpaRepository<Team, UUID>, LockableJpaRepository<Team, UUID> {

    /** One team per team-target — used by the owner-on-create uniqueness guard (ticket 3). */
    Optional<Team> findByTargetTypeAndTargetId(String targetType, UUID targetId);

    boolean existsByTargetTypeAndTargetId(String targetType, UUID targetId);
}
