package dev.dmitriikonovalov.example.usermgmt.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamMembershipRepository extends JpaRepository<TeamMembership, UUID> {

    List<TeamMembership> findByTeamId(UUID teamId);

    Page<TeamMembership> findByTeamId(UUID teamId, Pageable pageable);

    List<TeamMembership> findByUserId(UUID userId);

    /** The caller's membership on a specific team — the binding the resolve/authorize path reads. */
    Optional<TeamMembership> findByTeamIdAndUserId(UUID teamId, UUID userId);

    boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);
}
