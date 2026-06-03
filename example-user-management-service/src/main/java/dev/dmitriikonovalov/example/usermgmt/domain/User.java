package dev.dmitriikonovalov.example.usermgmt.domain;

import dev.dmitriikonovalov.opaabac.data.model.AbstractAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A user identity. Authentication stays Keycloak's job; this row holds only the demo-relevant
 * <em>profile</em>: a stable id, the IdP subject ({@code sub}) that links it to a Keycloak account,
 * and a display name. Not an {@link dev.dmitriikonovalov.opaabac.data.model.AbstractSecuredEntity} —
 * a user is a <em>principal</em>, not an authorizable resource of this service, so it carries no tags.
 */
@Entity
@Table(name = "app_user")
public class User extends AbstractAuditableEntity {

    /** The IdP subject ({@code sub}) — links this profile to the authenticating Keycloak account. */
    @Column(name = "subject", nullable = false, unique = true)
    private String subject;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    protected User() {
        // JPA
    }

    public User(UUID id, String subject, String displayName) {
        super(id);
        this.subject = subject;
        this.displayName = displayName;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
