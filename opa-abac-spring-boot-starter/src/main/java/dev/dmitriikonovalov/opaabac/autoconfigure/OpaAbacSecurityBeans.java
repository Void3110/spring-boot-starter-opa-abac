package dev.dmitriikonovalov.opaabac.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.security.AbacFilter;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import dev.dmitriikonovalov.opaabac.security.JwtClaimsSubjectExtractor;
import dev.dmitriikonovalov.opaabac.security.OpaMethodSecurityConfiguration;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorizeAuthorizationManager;
import dev.dmitriikonovalov.opaabac.security.ResourceResolutionSupport;
import dev.dmitriikonovalov.opaabac.security.SubjectClaimsConfig;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

    private static final Logger log = LoggerFactory.getLogger(OpaAbacSecurityBeans.class);

    /**
     * The default subject extractor — gated on the explicit
     * {@code opa.abac.subject.trust-forwarded-jwt=true} acknowledgment, because
     * {@link JwtClaimsSubjectExtractor} does <strong>no signature verification</strong> (it trusts a
     * validating gateway). Without the acknowledgment a refusing extractor is registered instead: every
     * request stays anonymous, every check denies (fail-closed), and a startup warning explains why —
     * a misdeployed app must never silently accept self-minted tokens. An app-provided
     * {@code AbacSubjectExtractor} bean overrides this entirely ({@code @ConditionalOnMissingBean}).
     *
     * <p>Claims parsing uses a private {@link ObjectMapper}: token payloads are external input, and the
     * extractor's behavior must not change with the application's Jackson customizations.
     */
    @Bean
    @ConditionalOnMissingBean
    public AbacSubjectExtractor abacSubjectExtractor(OpaAbacProperties properties) {
        OpaAbacProperties.Subject s = properties.getSubject();
        if (!s.isTrustForwardedJwt()) {
            log.warn("opa.abac.subject.trust-forwarded-jwt is not set — the default JWT subject extractor "
                    + "is DISABLED, every request is anonymous, and all ABAC checks deny (fail-closed). "
                    + "Set it to true ONLY behind a signature-validating gateway (the extractor does not "
                    + "verify signatures itself), or provide your own AbacSubjectExtractor bean.");
            return request -> Optional.empty();
        }
        SubjectClaimsConfig claims = new SubjectClaimsConfig(
                s.getIdClaim(), s.getRolesClaim(), s.getUsernameClaim(), s.getAttributeClaims(), s.isValidateExpiry());
        return new JwtClaimsSubjectExtractor(new ObjectMapper(), claims);
    }

    @Bean
    @ConditionalOnMissingBean
    public AbacFilter abacFilter(AbacSubjectExtractor abacSubjectExtractor) {
        return new AbacFilter(abacSubjectExtractor);
    }

    /**
     * The {@code @OpaPreAuthorize} manager. The {@link ResourceResolutionSupport} is present only when
     * the app registered an {@code AbacResourceResolver} and the {@code resource-resolution} kill-switch
     * is on; absent ({@code null}) the manager's behavior — and the OPA input it builds — is
     * byte-identical to the pre-resolution baseline.
     */
    @Bean
    @ConditionalOnMissingBean
    public OpaPreAuthorizeAuthorizationManager opaPreAuthorizeAuthorizationManager(
            OpaClient opaClient,
            RoleDefinitionSupplier roleDefinitionSupplier,
            ObjectProvider<ResourceResolutionSupport> resolutionSupport) {
        return new OpaPreAuthorizeAuthorizationManager(
                opaClient, roleDefinitionSupplier, resolutionSupport.getIfAvailable());
    }
}
