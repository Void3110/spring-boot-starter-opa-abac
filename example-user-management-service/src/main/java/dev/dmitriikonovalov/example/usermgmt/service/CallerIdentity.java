package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the calling user from the security context. The authenticated subject's {@code sub} maps
 * to a {@link User} (by its {@code subject} column); the user-service owns the link between an IdP
 * identity and a profile.
 *
 * <p>This is the seam that keeps every management decision tied to the <b>actor</b> of a grant, never
 * the service identity (the confused-deputy guard, {@code 00-DESIGN.md} hard rule 5). Until the
 * production security chain is wired (ticket 4), callers may pass an explicit creator/actor id as a
 * fallback, but the authenticated subject always wins.
 */
@Component
public class CallerIdentity {

    private final UserRepository users;

    public CallerIdentity(UserRepository users) {
        this.users = users;
    }

    /** The current authenticated subject's user id, if a subject is present and maps to a user. */
    public Optional<UUID> currentUserId() {
        return currentSubject().flatMap(subject -> users.findBySubject(subject).map(User::getId));
    }

    /**
     * The current authenticated subject's raw IdP {@code sub} (the same identity stored as a resource's
     * {@code created_by}) — the key the cross-service {@link
     * dev.dmitriikonovalov.opaabac.security.ownership.ResourceOwnershipResolver} compares against (Slice
     * B4). Distinct from {@link #currentUserId()} (the internal {@code User} id): ownership is keyed by the
     * {@code sub}, not the profile id.
     */
    public Optional<String> currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            return Optional.of(abac.getSubject().id());
        }
        return Optional.empty();
    }

    /**
     * The acting user id: the authenticated subject when present, otherwise the supplied fallback.
     * Throws when neither is available, so an unauthenticated call without a fallback cannot proceed.
     */
    public UUID requireActingUserId(UUID fallback) {
        return currentUserId().or(() -> Optional.ofNullable(fallback))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No acting user: request is unauthenticated and no fallback id was provided"));
    }
}
