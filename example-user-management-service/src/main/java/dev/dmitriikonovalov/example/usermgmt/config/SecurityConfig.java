package dev.dmitriikonovalov.example.usermgmt.config;

import dev.dmitriikonovalov.opaabac.security.AbacFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * The user-service's own security chain — the service <strong>dogfoods</strong> the starter exactly as
 * the catalog app does. The starter deliberately does not register a {@code SecurityFilterChain}; the
 * app owns it and installs the library's {@link AbacFilter} so a forwarded Bearer JWT becomes an
 * {@code AbacAuthentication} before authorization runs.
 *
 * <p>Stateless, CSRF off (token-authenticated API behind a gateway). Health + API docs are open. The
 * <b>management</b> API ({@code /api/v1/**}) requires authentication, and method-level
 * {@code @OpaPreAuthorize} (via {@link EnableMethodSecurity}) makes the per-endpoint team decision. The
 * <b>internal</b> resolve API ({@code /internal/**}, ticket 7) is not gateway-fronted — it is an
 * in-network attribute source the catalog calls — so it is permitted here and isolated by the network
 * in the rig (it is never exposed through the gateway). The catch-all is {@code authenticated()}, not
 * {@code permitAll()}: the broad demo actuator surface (env/beans/mappings/…) must not be readable
 * anonymously (retro-audit 2026-06-12) — local debugging uses a minted token. Error dispatches stay
 * permitted so problem+json renders for anonymous callers.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<AbacFilter> abacFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**")
                        .permitAll()
                        // The internal resolve API is in-network only (ticket 7), never gateway-fronted.
                        // Its list shapes are UNPAGINATED BY DESIGN (5.95): bounded machine-to-machine
                        // payloads on an isolated surface — the public list envelope does not apply here.
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        // No permitAll catch-all: actuator beyond health (env/beans/…) needs a subject.
                        .anyRequest().authenticated());

        AbacFilter filter = abacFilter.getIfAvailable();
        if (filter != null) {
            http.addFilterBefore(filter, AuthorizationFilter.class);
        }
        return http.build();
    }

    /**
     * Stop Boot from also auto-registering {@link AbacFilter} as a top-level servlet filter — it
     * belongs only inside the security chain (installed above); a second copy outside the chain would
     * have its {@code SecurityContextHolder} write cleared before authorization runs.
     *
     * <p>Gated on the same {@code opa.abac.enabled} property as the starter's auto-configuration: with
     * the starter off there is no filter, and an empty {@code FilterRegistrationBean} fails Tomcat
     * startup ({@code getDescription()} asserts a non-null filter even for a disabled registration).
     * A property condition, not {@code @ConditionalOnBean}: bean conditions evaluate while this user
     * config is parsed — <em>before</em> the deferred auto-configuration registers the
     * {@code AbacFilter} definition — so they cannot see the filter reliably (here the bean condition
     * silently never matched, leaving the suppression unregistered even on a guarded rig).
     */
    @Bean
    @ConditionalOnProperty(prefix = "opa.abac", name = "enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<AbacFilter> abacFilterRegistration(AbacFilter abacFilter) {
        FilterRegistrationBean<AbacFilter> registration = new FilterRegistrationBean<>(abacFilter);
        registration.setEnabled(false);
        return registration;
    }
}
