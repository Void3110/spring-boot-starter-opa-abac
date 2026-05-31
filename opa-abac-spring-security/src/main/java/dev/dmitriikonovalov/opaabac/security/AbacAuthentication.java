package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Spring Security {@link org.springframework.security.core.Authentication} that carries the ABAC
 * {@link AbacContext.Subject}.
 *
 * <p>Authorities are derived from the subject's roles as {@code ROLE_<role>}, so plain Spring role
 * checks (e.g. {@code hasRole}) keep working alongside the fine-grained OPA decision. The principal is
 * the subject id. Because the token is produced from an already-validated, gateway-forwarded identity,
 * it is created pre-authenticated.
 */
public final class AbacAuthentication extends AbstractAuthenticationToken {

    private final transient AbacContext.Subject subject;

    public AbacAuthentication(AbacContext.Subject subject) {
        super(toAuthorities(subject));
        this.subject = Objects.requireNonNull(subject, "subject");
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> toAuthorities(AbacContext.Subject subject) {
        if (subject == null) {
            return List.of();
        }
        return subject.roles().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    /** The typed ABAC subject (id, roles, attributes). */
    public AbacContext.Subject getSubject() {
        return subject;
    }

    @Override
    public Object getPrincipal() {
        return subject.id();
    }

    /** No credentials are held — the gateway already authenticated the caller. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return subject.id();
    }
}
