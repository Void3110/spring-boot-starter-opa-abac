package dev.dmitriikonovalov.opaabac.autoconfigure;

import tools.jackson.databind.json.JsonMapper;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.security.AbacFilter;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import dev.dmitriikonovalov.opaabac.security.JwtClaimsSubjectExtractor;
import dev.dmitriikonovalov.opaabac.security.OpaMethodSecurityConfiguration;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorizeAuthorizationManager;
import dev.dmitriikonovalov.opaabac.security.PrivilegedReadAuditPolicy;
import dev.dmitriikonovalov.opaabac.security.ResourceResolutionSupport;
import dev.dmitriikonovalov.opaabac.security.SubjectClaimsConfig;
import java.util.Optional;
import java.util.Set;
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
        return new JwtClaimsSubjectExtractor(JsonMapper.builder().build(), claims);
    }

    @Bean
    @ConditionalOnMissingBean
    public AbacFilter abacFilter(AbacSubjectExtractor abacSubjectExtractor) {
        return new AbacFilter(abacSubjectExtractor);
    }

    /**
     * The pre-Amendment-7 signature, retained for source/binary compatibility: this class is
     * {@code public} in a module published to Maven Central, so an already-compiled caller must keep
     * linking. It delegates with no audit policy — which is exactly what a caller predating the
     * privileged-read event should get. NOT a {@code @Bean}: the 4-arg method below is the one the
     * container calls, and two bean methods for one type would be ambiguous.
     *
     * <p>Prefer the overload taking {@link OpaAbacProperties}, which honours
     * {@code opa.abac.audit.privileged-read.*}. This shim carries no {@code @Deprecated}
     * annotation deliberately: it is not deprecated <em>behaviour</em> to be migrated off, it is a
     * link-compatibility artifact whose removal is a MAJOR-version decision, recorded as such in
     * ADR 0030 Amendment 7 rather than left as a standing lint suppression.
     */
    public OpaPreAuthorizeAuthorizationManager opaPreAuthorizeAuthorizationManager(
            OpaClient opaClient,
            RoleDefinitionSupplier roleDefinitionSupplier,
            ObjectProvider<ResourceResolutionSupport> resolutionSupport) {
        return new OpaPreAuthorizeAuthorizationManager(
                opaClient, roleDefinitionSupplier, resolutionSupport.getIfAvailable(), null);
    }

    /**
     * The {@code @OpaPreAuthorize} manager. The {@link ResourceResolutionSupport} is present only when
     * the app registered an {@code AbacResourceResolver} and the {@code resource-resolution} kill-switch
     * is on; absent ({@code null}) the manager's behavior — and the OPA input it builds — is
     * byte-identical to the pre-resolution baseline. The audit policy is built from
     * {@code opa.abac.audit.privileged-read.*} and is {@code null} when unconfigured (ADR 0030
     * Amendment 7).
     */
    @Bean
    @ConditionalOnMissingBean
    public OpaPreAuthorizeAuthorizationManager opaPreAuthorizeAuthorizationManager(
            OpaClient opaClient,
            RoleDefinitionSupplier roleDefinitionSupplier,
            ObjectProvider<ResourceResolutionSupport> resolutionSupport,
            OpaAbacProperties properties) {
        return new OpaPreAuthorizeAuthorizationManager(
                opaClient,
                roleDefinitionSupplier,
                resolutionSupport.getIfAvailable(),
                privilegedReadAuditPolicy(properties));
    }

    /**
     * The privileged-read audit trigger in the ADOPTER's vocabulary, or {@code null} when unconfigured
     * (ADR 0030 §8 Amendment 7 — a published starter must not fire an event keyed on this repo's own
     * example nouns). A configured {@code provenance} is the switch; the tier attribute defaults to
     * {@code env} and the values must be listed explicitly.
     */
    private static PrivilegedReadAuditPolicy privilegedReadAuditPolicy(OpaAbacProperties properties) {
        var configured = properties.getAudit().getPrivilegedRead();
        // ENTIRELY absent means off. HALF-configured means a typo, and silently disabling an audit
        // control on a typo is how oversight quietly stops happening — the record's own validation
        // fails startup instead, which is why it validates rather than normalizing. `isAnySet` counts
        // all THREE knobs, including a root-attribute set alone (its default value is not a
        // configuration, so tracking explicit assignment is what makes that case loud).
        if (!configured.isAnySet()) {
            return null;
        }
        return new PrivilegedReadAuditPolicy(
                configured.getProvenance(),
                configured.getRootAttribute(),
                Set.copyOf(configured.getRootValues()));
    }
}
