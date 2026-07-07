package dev.dmitriikonovalov.opaabac.autoconfigure;

import dev.dmitriikonovalov.opaabac.keycloak.directory.KeycloakDirectoryProperties;
import dev.dmitriikonovalov.opaabac.keycloak.directory.KeycloakUserDirectory;
import dev.dmitriikonovalov.opaabac.security.directory.NoOpUserDirectory;
import dev.dmitriikonovalov.opaabac.security.directory.UserDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the user-directory port (the identity-search seam, ADR 0020 §3): the
 * {@link KeycloakUserDirectory} when the optional {@code opa-abac-keycloak-directory} module is on the
 * classpath <strong>and</strong> {@code opa.abac.directory.keycloak.enabled=true}; otherwise the
 * {@link NoOpUserDirectory}, so a {@link UserDirectory} injection point always resolves and the
 * zero-config behavior is the fail-closed empty — exactly the B3 Resilience4j optional-module pattern
 * ({@code @ConditionalOnClass} + {@code @ConditionalOnProperty}, lean-starter preserved).
 *
 * <p>Both beans are {@code @ConditionalOnMissingBean(UserDirectory.class)} so an adopter-supplied
 * directory (LDAP, SCIM, a static list) always wins. The nested Keycloak config registers before this
 * class's own fallback method (member configurations are processed first), so when Keycloak is opted in
 * the fallback sees it and backs off — the on-state and off-state are both single-bean contexts, proven
 * by the I3a–I3c slice tests.
 *
 * <p>The Keycloak module is {@code compileOnly} on the starter (an adopter who does not add it never
 * drags Keycloak in); the {@code @ConditionalOnClass} keys off the admin-client type by name so this
 * config parses safely when the module is absent.
 */
@Configuration(proxyBeanMethods = false)
class OpaDirectoryAutoConfiguration {

    /**
     * The fail-closed default: empty for every query. Backs off to the Keycloak bean (registered first
     * when opted in) or to any adopter-supplied {@link UserDirectory}.
     */
    @Bean
    @ConditionalOnMissingBean(UserDirectory.class)
    UserDirectory noOpUserDirectory() {
        return new NoOpUserDirectory();
    }

    /**
     * The Keycloak implementation — present only when the optional module is on the classpath and the
     * adopter explicitly opted in. Construction does no I/O (the token grant is lazy), so a down or
     * misconfigured Keycloak surfaces as the no-oracle empty in {@code search()}, never a startup
     * failure. The bean is {@code AutoCloseable}; the context closes the underlying client on shutdown.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.keycloak.admin.client.Keycloak")
    @ConditionalOnProperty(prefix = "opa.abac.directory.keycloak", name = "enabled", havingValue = "true")
    @EnableConfigurationProperties(KeycloakDirectoryProperties.class)
    static class KeycloakDirectoryConfiguration {

        @Bean
        @ConditionalOnMissingBean(UserDirectory.class)
        UserDirectory keycloakUserDirectory(KeycloakDirectoryProperties properties) {
            return new KeycloakUserDirectory(properties);
        }
    }
}
