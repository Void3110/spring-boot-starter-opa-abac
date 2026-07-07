package dev.dmitriikonovalov.opaabac.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.keycloak.directory.KeycloakDirectoryProperties;
import dev.dmitriikonovalov.opaabac.keycloak.directory.KeycloakUserDirectory;
import dev.dmitriikonovalov.opaabac.security.directory.DirectoryUser;
import dev.dmitriikonovalov.opaabac.security.directory.NoOpUserDirectory;
import dev.dmitriikonovalov.opaabac.security.directory.UserDirectory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link ApplicationContextRunner} slice tests for the user-directory wiring (Slice-2 QA I3a–I3c): the
 * off-state is first-class (bare adopter → {@link NoOpUserDirectory}, never a startup failure), the
 * opt-in state is single-bean Keycloak, and an adopter-supplied {@link UserDirectory} always wins.
 */
class OpaDirectoryAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpaAbacAutoConfiguration.class));

    private static final String[] KEYCLOAK_PROPERTIES = {
        "opa.abac.directory.keycloak.enabled=true",
        "opa.abac.directory.keycloak.server-url=http://keycloak:8888",
        "opa.abac.directory.keycloak.realm=catalog-demo",
        "opa.abac.directory.keycloak.client-id=catalog-directory",
        "opa.abac.directory.keycloak.client-secret=demo-secret"
    };

    @Test // I3a — enabled unset (module on the classpath) → the NoOp, no Keycloak bean
    void noOp_whenEnabledUnset() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(UserDirectory.class);
            assertThat(context.getBean(UserDirectory.class)).isInstanceOf(NoOpUserDirectory.class);
            assertThat(context).doesNotHaveBean(KeycloakUserDirectory.class);
        });
    }

    @Test // I3a — the module absent entirely → the NoOp even with enabled=true (no startup failure)
    void noOp_whenModuleAbsent() {
        runner.withClassLoader(new FilteredClassLoader("org.keycloak", "dev.dmitriikonovalov.opaabac.keycloak"))
                .withPropertyValues(KEYCLOAK_PROPERTIES)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UserDirectory.class);
                    assertThat(context.getBean(UserDirectory.class).getClass().getSimpleName())
                            .isEqualTo("NoOpUserDirectory");
                });
    }

    @Test // I3b — module present + enabled=true + properties → the Keycloak bean, no NoOp
    void keycloakDirectory_whenEnabled() {
        runner.withPropertyValues(KEYCLOAK_PROPERTIES).run(context -> {
            assertThat(context).hasSingleBean(UserDirectory.class);
            assertThat(context.getBean(UserDirectory.class)).isInstanceOf(KeycloakUserDirectory.class);
            KeycloakDirectoryProperties props = context.getBean(KeycloakDirectoryProperties.class);
            assertThat(props.getServerUrl()).isEqualTo("http://keycloak:8888");
            assertThat(props.getRealm()).isEqualTo("catalog-demo");
            assertThat(props.getClientId()).isEqualTo("catalog-directory");
        });
    }

    @Test // I3c — an adopter-supplied UserDirectory wins over both library beans
    void adopterDirectoryWins() {
        runner.withPropertyValues(KEYCLOAK_PROPERTIES)
                .withUserConfiguration(AdopterDirectory.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(UserDirectory.class);
                    assertThat(context.getBean(UserDirectory.class)).isInstanceOf(StubDirectory.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class AdopterDirectory {
        @Bean
        UserDirectory stubDirectory() {
            return new StubDirectory();
        }
    }

    static final class StubDirectory implements UserDirectory {
        @Override
        public List<DirectoryUser> search(String query, int limit) {
            return List.of(new DirectoryUser("stub", "stub"));
        }
    }
}
