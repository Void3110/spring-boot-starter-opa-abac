package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Enables Spring Data JPA auditing so the base entities' {@code @CreatedBy}/{@code @LastModifiedBy}
 * and {@code @CreatedDate}/{@code @LastModifiedDate} populate automatically.
 *
 * <p><b>Date type.</b> The base timestamps are {@link OffsetDateTime} (mapped to {@code timestamptz}).
 * Spring Data's default auditing date source produces a {@code LocalDateTime}, which it then cannot
 * convert to {@code OffsetDateTime} (its converter doesn't support that target). So we supply a
 * {@link DateTimeProvider} that returns an {@code OffsetDateTime} directly — auditing then uses it
 * as-is, no conversion. Referenced via {@code dateTimeProviderRef}.
 *
 * <p><b>Auditor.</b> The real principal: the {@code sub} (a {@code UUID}) of the current
 * {@link AbacAuthentication}, populated by the library's {@code AbacFilter} from the forwarded JWT.
 * {@link Optional#empty()} when unauthenticated (or when the subject id is not a UUID), which leaves
 * the audit columns null rather than guessing.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class AuditingConfig {

    @Bean
    AuditorAware<UUID> auditorAware() {
        return () -> currentPrincipalId();
    }

    private static Optional<UUID> currentPrincipalId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AbacAuthentication abac && abac.isAuthenticated()) {
            try {
                return Optional.of(UUID.fromString(abac.getSubject().id()));
            } catch (IllegalArgumentException e) {
                return Optional.empty(); // subject id is not a UUID — don't guess
            }
        }
        return Optional.empty();
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now());
    }
}
