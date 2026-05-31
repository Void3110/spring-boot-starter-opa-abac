package dev.dmitriikonovalov.example.catalog.config;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

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
 * <p><b>Auditor.</b> For now a <strong>fixed demo principal</strong>: the gateway terminates identity
 * but the service does no JWT extraction yet (that lands with the security library). A later
 * Phase-3 slice replaces this bean with one that reads the authenticated principal; the base classes
 * need no change for that — only this bean does.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class AuditingConfig {

    /** A stable, recognizable demo principal id used until real identity extraction lands. */
    static final UUID DEMO_PRINCIPAL = UUID.fromString("00000000-0000-0000-0000-00000000de70");

    @Bean
    AuditorAware<UUID> auditorAware() {
        return () -> Optional.of(DEMO_PRINCIPAL);
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now());
    }
}
