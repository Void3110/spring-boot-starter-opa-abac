package dev.dmitriikonovalov.example.usermgmt.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permissive security for the ticket-2 persistence ITs. The service has no production
 * {@code SecurityConfig} yet (that lands in ticket 4 with the management API), but
 * {@code spring-boot-starter-security} is on the classpath, so Boot's default chain would otherwise
 * lock every endpoint. This test-only chain permits everything so the read/create CRUD tests can
 * exercise persistence without authorization — authorization is covered from ticket 4 on.
 */
@TestConfiguration
public class PermissiveSecurityTestConfig {

    @Bean
    SecurityFilterChain permissiveChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
