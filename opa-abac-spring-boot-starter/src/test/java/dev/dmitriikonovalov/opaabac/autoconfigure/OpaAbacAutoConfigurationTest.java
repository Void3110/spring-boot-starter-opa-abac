package dev.dmitriikonovalov.opaabac.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.NoOpRoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PerTypePolicyPathResolver;
import dev.dmitriikonovalov.opaabac.core.PolicyPathResolver;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.security.AbacFilter;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorizeAuthorizationManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.SecurityFilterChain;

/** {@link ApplicationContextRunner} slice tests for the starter — QA cases U30–U34. */
class OpaAbacAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpaAbacAutoConfiguration.class));

    @Test // U30 — enabled + security on classpath → all spine beans present
    void allBeansPresent_whenEnabled() {
        runner.withPropertyValues("opa.abac.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(OpaClient.class);
            assertThat(context).hasSingleBean(PolicyPathResolver.class);
            assertThat(context).hasSingleBean(RoleDefinitionSupplier.class);
            assertThat(context).hasSingleBean(AbacSubjectExtractor.class);
            assertThat(context).hasSingleBean(AbacFilter.class);
            assertThat(context).hasSingleBean(OpaPreAuthorizeAuthorizationManager.class);
            // the starter never creates a SecurityFilterChain
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
        });
    }

    @Test // U31 — disabled → no spine beans
    void noBeans_whenDisabled() {
        runner.withPropertyValues("opa.abac.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OpaClient.class);
            assertThat(context).doesNotHaveBean(PolicyPathResolver.class);
            assertThat(context).doesNotHaveBean(RoleDefinitionSupplier.class);
            assertThat(context).doesNotHaveBean(AbacFilter.class);
        });
    }

    @Test // U32 — user @Bean OpaClient / RoleDefinitionSupplier → starter backs off
    void userBeansWin() {
        runner.withUserConfiguration(UserOverrides.class).run(context -> {
            assertThat(context).hasSingleBean(OpaClient.class);
            assertThat(context.getBean(OpaClient.class)).isInstanceOf(StubOpaClient.class);
            assertThat(context.getBean(RoleDefinitionSupplier.class)).isInstanceOf(StubSupplier.class);
        });
    }

    @Test // U33 — security/web removed → only the core beans, no security beans, no chain
    void onlyCoreBeans_whenSecurityAbsent() {
        runner.withClassLoader(new FilteredClassLoader(
                org.springframework.security.web.SecurityFilterChain.class,
                org.springframework.web.filter.OncePerRequestFilter.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(OpaClient.class);
                    assertThat(context).hasSingleBean(PolicyPathResolver.class);
                    assertThat(context).hasSingleBean(RoleDefinitionSupplier.class);
                    assertThat(context).doesNotHaveBean(AbacSubjectExtractor.class);
                    assertThat(context).doesNotHaveBean(AbacFilter.class);
                    assertThat(context).doesNotHaveBean(OpaPreAuthorizeAuthorizationManager.class);
                });
    }

    @Test // U34 — property binding
    void propertiesBind() {
        runner.withPropertyValues(
                "opa.abac.base-url=http://opa:8181",
                "opa.abac.decision-field=permit",
                "opa.abac.policy-prefix=catalog",
                "opa.abac.subject.roles-claim=authz.groups",
                "opa.abac.subject.validate-expiry=false")
                .run(context -> {
                    OpaAbacProperties props = context.getBean(OpaAbacProperties.class);
                    assertThat(props.getBaseUrl()).isEqualTo("http://opa:8181");
                    assertThat(props.getDecisionField()).isEqualTo("permit");
                    assertThat(props.getPolicyPrefix()).isEqualTo("catalog");
                    assertThat(props.getSubject().getRolesClaim()).isEqualTo("authz.groups");
                    assertThat(props.getSubject().isValidateExpiry()).isFalse();
                    // resolver reflects the prefix
                    assertThat(context.getBean(PolicyPathResolver.class)).isInstanceOf(PerTypePolicyPathResolver.class);
                });
    }

    @Test // default supplier is the no-op
    void defaultSupplier_isNoOp() {
        runner.run(context ->
                assertThat(context.getBean(RoleDefinitionSupplier.class)).isInstanceOf(NoOpRoleDefinitionSupplier.class));
    }

    // --- user overrides ------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class UserOverrides {
        @Bean
        OpaClient opaClient() {
            return new StubOpaClient();
        }

        @Bean
        RoleDefinitionSupplier roleDefinitionSupplier() {
            return new StubSupplier();
        }
    }

    static class StubOpaClient implements OpaClient {
        @Override
        public boolean allow(dev.dmitriikonovalov.opaabac.core.AbacContext context) {
            return true;
        }
    }

    static class StubSupplier implements RoleDefinitionSupplier {
        @Override
        public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
            return Optional.empty();
        }
    }
}
