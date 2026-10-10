package dev.dmitriikonovalov.example.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.mcp.authz.MemoizingPrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import tools.jackson.databind.ObjectMapper;

/**
 * U7 — the example's ceiling wiring honors the starter's {@code opa.abac.resolve-memo.enabled}: the memo
 * that used to decorate this source as a {@code RoleDefinitionSupplier} still applies (default on), and
 * OFF still serves the raw supplier.
 */
class CeilingMemoWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CeilingOnly.class);

    @Test
    void theCeilingIsMemoizedByDefault() {
        runner.run(context -> assertThat(context.getBean(PrincipalCeilingSupplier.class))
                .isInstanceOf(MemoizingPrincipalCeilingSupplier.class));
    }

    @Test
    void resolveMemoOffServesTheRawSupplier() {
        runner.withPropertyValues("opa.abac.resolve-memo.enabled=false")
                .run(context -> assertThat(context.getBean(PrincipalCeilingSupplier.class))
                        .isInstanceOf(TypeLevelPrincipalCeilingSupplier.class));
    }

    /** Only the ceiling bean method of the real configuration, without the gate it feeds. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ExampleAuthzProperties.class)
    static class CeilingOnly {

        @Bean
        PrincipalCeilingSupplier principalCeilingSupplier(
                ExampleAuthzProperties properties, org.springframework.core.env.Environment environment) {
            return new ExampleAuthzConfiguration()
                    .principalCeilingSupplier(new ObjectMapper(), properties, environment);
        }
    }
}
