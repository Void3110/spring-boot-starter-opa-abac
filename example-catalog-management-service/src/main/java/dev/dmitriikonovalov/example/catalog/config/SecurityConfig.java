package dev.dmitriikonovalov.example.catalog.config;

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
 * <p>With the starter <strong>off</strong> ({@code opa.abac.enabled=false} — the unguarded-baseline
 * rig, ADR 0021 §2) there is no {@link AbacFilter}, so nothing can turn the forwarded Bearer into an
 * {@code Authentication} and {@code authenticated()} would be unsatisfiable. The chain then serves the
 * API on gateway trust alone (APISIX still validates the token upstream) while keeping the audit
 * posture: actuator beyond health is explicitly denied.
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
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        AbacFilter filter = abacFilter.getIfAvailable();
        if (filter != null) {
            http.authorizeHttpRequests(auth -> auth
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
                            .anyRequest().authenticated())
                    .addFilterBefore(filter, AuthorizationFilter.class);
        } else {
            // The unguarded-baseline posture (see the class javadoc): the API serves on gateway trust;
            // the actuator surface beyond health stays explicitly denied, not just unauthenticatable.
            http.authorizeHttpRequests(auth -> auth
                    .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                    .requestMatchers(
                            "/actuator/health",
                            "/actuator/health/**",
                            "/swagger-ui.html",
                            "/swagger-ui/**",
                            "/v3/api-docs/**")
                    .permitAll()
                    .requestMatchers("/internal/**").permitAll()
                    .requestMatchers("/api/v1/**").permitAll()
                    .anyRequest().denyAll());
        }
        return http.build();
    }

    /**
     * Stop Boot from <em>also</em> auto-registering {@link AbacFilter} as a top-level servlet filter.
     * It belongs only inside the security chain (installed above); a second copy running outside the
     * chain would have its {@code SecurityContextHolder} write cleared by Spring Security's
     * {@code SecurityContextHolderFilter} before authorization runs.
     *
     * <p>Gated on the same {@code opa.abac.enabled} property as the starter's auto-configuration: with
     * the starter off there is no filter, and an <em>empty</em> {@code FilterRegistrationBean} fails
     * Tomcat startup ({@code RegistrationBean.onStartup} calls {@code getDescription()}, which asserts
     * a non-null filter even for a disabled registration). A property condition, not
     * {@code @ConditionalOnBean}: bean conditions evaluate while this user config is parsed —
     * <em>before</em> the deferred auto-configuration registers the {@code AbacFilter} definition —
     * so they cannot see the filter reliably.
     */
    @Bean
    @ConditionalOnProperty(prefix = "opa.abac", name = "enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<AbacFilter> abacFilterRegistration(AbacFilter abacFilter) {
        FilterRegistrationBean<AbacFilter> registration = new FilterRegistrationBean<>(abacFilter);
        registration.setEnabled(false);
        return registration;
    }
}
