package dev.dmitriikonovalov.example.mcp.config;

import dev.dmitriikonovalov.opaabac.security.AbacFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * The MCP server's security chain — the same shape the catalog service uses: stateless, CSRF off (a
 * token-authenticated API behind a gateway), with the library's {@link AbacFilter} turning the forwarded
 * bearer into an {@code AbacAuthentication}.
 *
 * <p>Only the health endpoint is open. Everything else — the MCP transport endpoints included — requires
 * an authenticated caller, so an anonymous client cannot even enumerate the tool surface. That is a
 * coarse, transport-level check and deliberately <em>not</em> the authorization this slice is about: it
 * establishes that there is a principal, and the tool-gate (T4) then decides what that principal, and the
 * agent acting for them, may actually invoke.
 *
 * <p>With the starter disabled there is no {@link AbacFilter} to build an authentication, so
 * {@code authenticated()} would be unsatisfiable; the chain then serves on gateway trust alone, matching
 * the catalog service's unguarded-baseline posture.
 */
@Configuration
@EnableWebSecurity
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
                            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                            .anyRequest().authenticated())
                    .addFilterBefore(filter, AuthorizationFilter.class);
        } else {
            http.authorizeHttpRequests(auth -> auth
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .anyRequest().permitAll());
        }
        return http.build();
    }
}
