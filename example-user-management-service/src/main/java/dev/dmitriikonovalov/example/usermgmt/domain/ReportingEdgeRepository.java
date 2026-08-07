package dev.dmitriikonovalov.example.usermgmt.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportingEdgeRepository extends JpaRepository<ReportingEdge, UUID> {

    /** One BFS frontier expansion: every edge leaving any of the given managers. */
    List<ReportingEdge> findByManagerIdIn(Collection<UUID> managerIds);

    /** A single manager's outgoing edges — the declarative replace set. */
    List<ReportingEdge> findByManagerId(UUID managerId);
}
