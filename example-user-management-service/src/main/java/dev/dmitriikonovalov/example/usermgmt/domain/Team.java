package dev.dmitriikonovalov.example.usermgmt.domain;

import dev.dmitriikonovalov.opaabac.data.model.AbstractSecuredEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A team — the durable owner of a resource. The <b>team-target</b> ({@link #targetType} +
 * {@link #targetId}) points at the resource the team governs (e.g. a {@code catalog} and its UUID):
 * resource→team indirection, so an <em>owner</em> role can sit on a person and be transferred while
 * the team's grant on the resource persists.
 *
 * <p>{@code Team} is an {@link AbstractSecuredEntity} — its ABAC resource type is {@code "team"}. The
 * service dogfoods the starter: the management API (membership, role-defs, transfer) is
 * {@code @OpaPreAuthorize}-secured against resource type {@code "team"}, with the caller's effective
 * role <em>on this team</em> resolved server-side.
 */
@Entity
@Table(name = "team")
public class Team extends AbstractSecuredEntity {

    @Column(nullable = false)
    private String name;

    /** The team-target's resource type, e.g. {@code "catalog"}. */
    @Column(name = "target_type", nullable = false)
    private String targetType;

    /** The team-target's resource id. */
    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    protected Team() {
        // JPA
    }

    public Team(UUID id, String name, String targetType, UUID targetId) {
        super(id);
        this.name = name;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    @Override
    public String abacResourceType() {
        return "team";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public void setTargetId(UUID targetId) {
        this.targetId = targetId;
    }
}
