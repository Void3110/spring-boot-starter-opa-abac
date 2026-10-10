package dev.dmitriikonovalov.opaabac.autoconfigure.mcp;

import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import dev.dmitriikonovalov.opaabac.security.AbacFilter;
import jakarta.servlet.DispatcherType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * A minimal MCP server on Spring MVC for the per-type integration tests (I2–I6): the starter's
 * auto-configuration (which installs the tool-gate), a security chain carrying the starter's
 * {@link AbacFilter} (the forwarded bearer becomes the caller), one declared tool — and nothing else of an
 * application. The tool bean is chosen per test ({@link SyncProbeTool} or {@link AsyncProbeTool} — top-level
 * classes, because a configuration nested in this one would always be registered), since an {@code ASYNC}
 * server only scans reactive-returning {@code @McpTool} methods.
 *
 * <p>JPA and the datasource are excluded: the starter's test classpath carries {@code opa-abac-spring-data}
 * (and through it Spring Data JPA), and this server owns no persistence.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
})
public class McpGateTestApplication {

    static final String TOOL = "probe";

    /** How many times the tool BODY ran — the proof a denied call never reached it. */
    static final AtomicInteger BODY_RUNS = new AtomicInteger();

    @Bean
    SecurityFilterChain mcpChain(HttpSecurity http, AbacFilter abacFilter) {
        // The example's chain shape: the streamable transport re-dispatches ASYNC after the handler ran, and
        // an ASYNC dispatch only exists for a request that already passed this chain on its initial one.
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(abacFilter, AuthorizationFilter.class)
                .build();
    }

    @Bean
    ToolRegistry toolRegistry() {
        return new ToolRegistry(List.of(new ToolDescriptor(TOOL, "view", "READ", "catalog", Set.of("low"))));
    }

    @Bean
    PrincipalCeilingSupplier ceilingSource() {
        RoleDefinition ceiling = new RoleDefinition(
                "type-level", Map.of(), Map.of("catalog", List.of("READ")), Map.of(), Map.of(), null);
        return (subject, type) -> Optional.of(ceiling);
    }
}
