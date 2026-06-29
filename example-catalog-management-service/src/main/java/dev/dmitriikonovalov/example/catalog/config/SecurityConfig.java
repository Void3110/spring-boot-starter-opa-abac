package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.opaabac.security.AbacFilter;
import org.springframework.beans.factory.ObjectProvider;
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
 * The application's security chain. The starter deliberately does <strong>not</strong> register a
 * {@code SecurityFilterChain} — the app owns it — and here we install the library's {@link AbacFilter}
 * so the forwarded Bearer JWT becomes an {@code AbacAuthentication} before authorization runs.
 *
 * <p>Stateless, CSRF off (a token-authenticated API behind a gateway). Health and API docs are open;
 * everything under {@code /api/v1/**} requires authentication, and method-level {@code @OpaPreAuthorize}
 * (enabled by {@link EnableMethodSecurity}) makes the fine-grained OPA decision per endpoint. The
 * catch-all is {@code authenticated()}, not {@code permitAll()}: the broad demo actuator surface
 * (env/beans/mappings/…) must not be readable anonymously (retro-audit 2026-06-12) — local debugging
 * uses a minted token. Error dispatches stay permitted so problem+json renders for anonymous callers.
 *
 * <p>{@code AbacFilter} is injected via {@link ObjectProvider} so a permissive test profile that turns
 * the starter off (no {@code AbacFilter} bean) still builds a valid chain.
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
                        // Slice B4 (ADR 0019): the catalog now exposes an in-network ownership read
                        // (GET /internal/catalog/{id}/created-by) the user-service's
                        // DiscoveryOwnershipResolver calls. Like the user-service's resolve API, /internal/**
                        // is permitted here and isolated by the network boundary — it is NEVER gateway-
                        // fronted (the gateway proxies only /api/v1/** + Keycloak; see infra/apisix). Direct
                        // exposure would let anyone read a creator id; the gateway routing (T8) keeps it off.
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
     * Stop Boot from <em>also</em> auto-registering {@link AbacFilter} as a top-level servlet filter.
     * It belongs only inside the security chain (installed above); a second copy running outside the
     * chain would have its {@code SecurityContextHolder} write cleared by Spring Security's
     * {@code SecurityContextHolderFilter} before authorization runs.
     */
    @Bean
    FilterRegistrationBean<AbacFilter> abacFilterRegistration(ObjectProvider<AbacFilter> abacFilter) {
        FilterRegistrationBean<AbacFilter> registration = new FilterRegistrationBean<>();
        AbacFilter filter = abacFilter.getIfAvailable();
        if (filter != null) {
            registration.setFilter(filter);
        }
        registration.setEnabled(false);
        return registration;
    }
}
