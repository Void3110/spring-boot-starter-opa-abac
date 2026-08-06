package dev.dmitriikonovalov.example.usermgmt.domain;

import dev.dmitriikonovalov.opaabac.data.model.AbstractAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

/**
 * One edge of the <b>reporting relation</b>: {@code manager_id} directly manages {@code report_id}.
 * The relation is people-structure, deliberately <em>not</em> modelled on the resource hierarchy
 * (ADR 0029) — it has a different shape, lifetime and owner.
 *
 * <p>In a real deployment this projection is mastered by HR and provisioned through the IdP; here it
 * is a fixture seeded through {@code /internal/bootstrap/reporting-edges}, exactly as teams and
 * memberships are. It is <b>not</b> an authorizable resource of this service, so it carries no tags —
 * like {@link User}, it is a relation between principals.
 *
 * <p>The pair is unique ({@code uq_reporting_edge_pair}), so the same edge written twice converges to
 * one row. A self-edge and any edge that would close a cycle are rejected <b>on write</b>; the
 * read-time derivation ({@code SupervisionService}) additionally fails <b>closed to empty</b> should a
 * cycle nevertheless exist in the data.
 */
@Entity
@Table(
        name = "reporting_edge",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_reporting_edge_pair",
                columnNames = {"manager_id", "report_id"}))
public class ReportingEdge extends AbstractAuditableEntity {

    /** The managing user ({@code app_user.id}) — the supervisor side of the edge. */
    @Column(name = "manager_id", nullable = false)
    private UUID managerId;

    /** The managed user ({@code app_user.id}) — the direct report. */
    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    protected ReportingEdge() {
        // JPA
    }

    public ReportingEdge(UUID id, UUID managerId, UUID reportId) {
        super(id);
        this.managerId = managerId;
        this.reportId = reportId;
    }

    public UUID getManagerId() {
        return managerId;
    }

    public void setManagerId(UUID managerId) {
        this.managerId = managerId;
    }

    public UUID getReportId() {
        return reportId;
    }

    public void setReportId(UUID reportId) {
        this.reportId = reportId;
    }
}
