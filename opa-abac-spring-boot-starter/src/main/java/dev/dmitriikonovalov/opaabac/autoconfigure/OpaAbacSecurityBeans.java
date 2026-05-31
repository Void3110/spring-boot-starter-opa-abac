package dev.dmitriikonovalov.opaabac.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.security.AbacFilter;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import dev.dmitriikonovalov.opaabac.security.JwtClaimsSubjectExtractor;
import dev.dmitriikonovalov.opaabac.security.OpaMethodSecurityConfiguration;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorizeAuthorizationManager;
import dev.dmitriikonovalov.opaabac.security.SubjectClaimsConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The Spring-Security beans of the OPA ABAC starter. Imported by
 * {@link OpaAbacAutoConfiguration.SecurityAutoConfiguration} only when the security/web classes are
 * present, so the security types here never load in a non-security app.
 *
 * <p>Every bean is {@link ConditionalOnMissingBean}. This config <strong>does not</strong> declare a
 * {@code SecurityFilterChain}; it imports {@link OpaMethodSecurityConfiguration} (the
 * {@code @OpaPreAuthorize} advisor) and exposes the extractor, the {@link AbacFilter}, and the
 * authorization manager for the app to use.
 */
@Configuration(proxyBeanMethods = false)
@Import(OpaMethodSecurityConfiguration.class)
public class OpaAbacSecurityBeans {

    @Bean
    @ConditionalOnMissingBean
    public AbacSubjectExtractor abacSubjectExtractor(ObjectMapper objectMapper, OpaAbacProperties properties) {
        OpaAbacProperties.Subject s = properties.getSubject();
        SubjectClaimsConfig claims = new SubjectClaimsConfig(
                s.getIdClaim(), s.getRolesClaim(), s.getUsernameClaim(), s.getAttributeClaims(), s.isValidateExpiry());
        return new JwtClaimsSubjectExtractor(objectMapper, claims);
    }

    @Bean
    @ConditionalOnMissingBean
    public AbacFilter abacFilter(AbacSubjectExtractor abacSubjectExtractor) {
        return new AbacFilter(abacSubjectExtractor);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpaPreAuthorizeAuthorizationManager opaPreAuthorizeAuthorizationManager(
            OpaClient opaClient, RoleDefinitionSupplier roleDefinitionSupplier) {
        return new OpaPreAuthorizeAuthorizationManager(opaClient, roleDefinitionSupplier);
    }
}
