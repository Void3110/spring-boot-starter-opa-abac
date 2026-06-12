package dev.dmitriikonovalov.opaabac.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration properties for the OPA ABAC starter.
 *
 * <pre>
 * opa:
 *   abac:
 *     enabled: true
 *     base-url: http://localhost:8181
 *     policy-prefix: catalog
 *     timeout: 5s
 *     decision-field: allow
 *     subject:
 *       trust-forwarded-jwt: false
 *       id-claim: sub
 *       roles-claim: realm_access.roles
 *       username-claim: preferred_username
 *       attribute-claims: []
 *       validate-expiry: true
 *     partial-eval:
 *       enabled: true
 *       allowlist-fallback: true
 * </pre>
 */
@ConfigurationProperties(prefix = "opa.abac")
public class OpaAbacProperties {

    /** Master switch for the ABAC auto-configuration. */
    private boolean enabled = true;

    /** Base URL of the OPA server. */
    private String baseUrl = "http://localhost:8181";

    /** Policy path prefix under OPA's data document (e.g. {@code catalog}); blank for none. */
    private String policyPrefix = "";

    /** Per-request evaluation timeout. */
    private Duration timeout = Duration.ofSeconds(5);

    /** Boolean field read from OPA's {@code result} (e.g. {@code result.allow}). */
    private String decisionField = "allow";

    /** How the JWT subject is read from claims. */
    @NestedConfigurationProperty
    private Subject subject = new Subject();

    /** Partial-evaluation (list data-filtering) settings. */
    @NestedConfigurationProperty
    private PartialEval partialEval = new PartialEval();

    /** N-level hierarchical authorization settings (opt-in, default-off). */
    @NestedConfigurationProperty
    private Hierarchy hierarchy = new Hierarchy();

    /** Resource-resolution (attribute-rich pre-authorization) settings. */
    @NestedConfigurationProperty
    private ResourceResolution resourceResolution = new ResourceResolution();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPolicyPrefix() {
        return policyPrefix;
    }

    public void setPolicyPrefix(String policyPrefix) {
        this.policyPrefix = policyPrefix;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getDecisionField() {
        return decisionField;
    }

    public void setDecisionField(String decisionField) {
        this.decisionField = decisionField;
    }

    public Subject getSubject() {
        return subject;
    }

    public void setSubject(Subject subject) {
        this.subject = subject;
    }

    public PartialEval getPartialEval() {
        return partialEval;
    }

    public void setPartialEval(PartialEval partialEval) {
        this.partialEval = partialEval;
    }

    public Hierarchy getHierarchy() {
        return hierarchy;
    }

    public void setHierarchy(Hierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public ResourceResolution getResourceResolution() {
        return resourceResolution;
    }

    public void setResourceResolution(ResourceResolution resourceResolution) {
        this.resourceResolution = resourceResolution;
    }

    /**
     * Partial-evaluation (list data-filtering) settings. {@code enabled} is a true kill-switch: when off,
     * {@code AbacQueryService} degrades to the coarse pre-filtering path (scope + one decision), never
     * fail-open. {@code allowlistFallback} runs a post-fetch batch re-check for residuals that don't fully
     * reduce to SQL.
     */
    public static class PartialEval {

        /** Master switch for partial-eval list filtering (a kill-switch, never fail-open when off). */
        private boolean enabled = true;

        /** Run the post-fetch batch allowlist for residuals flagged not-fully-SQL. */
        private boolean allowlistFallback = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAllowlistFallback() {
            return allowlistFallback;
        }

        public void setAllowlistFallback(boolean allowlistFallback) {
            this.allowlistFallback = allowlistFallback;
        }
    }

    /**
     * N-level hierarchical authorization settings (Slice 5.5-A). <strong>Opt-in, default-off</strong>: a
     * grant on an ancestor inherits down to a descendant only when {@code enabled} is {@code true} AND the
     * relation is declared {@link #inheritable}. When off, every type is authorized on itself, as before.
     *
     * <pre>
     * opa:
     *   abac:
     *     hierarchy:
     *       enabled: true
     *       resolver: ltree           # ltree (default, materialized path) | cte (live parent walk)
     *       max-depth: 32             # mandatory bound; a deeper chain throws (fail-closed)
     *       inheritable:              # structural declaration: childType -> [ancestorType...]
     *         category: [catalog]
     *         product: [category, catalog]
     * </pre>
     *
     * <p>{@code inheritable} mirrors the {@code inheritable[childType][ancestorType]} OPA data the Rego
     * inheritance clause reads; it is exposed here so an app can publish a single source of truth to OPA.
     * The library uses {@code enabled}/{@code resolver}/{@code maxDepth} to wire the resolver beans.
     */
    public static class Hierarchy {

        /** Master switch for hierarchical authorization. <strong>Default false (opt-in).</strong> */
        private boolean enabled = false;

        /** Which {@code AncestorResolver} to wire: {@code ltree} (default) or {@code cte}. */
        private String resolver = "ltree";

        /** Mandatory depth bound; a chain deeper than this throws (fail-closed), never truncates. */
        private int maxDepth = 32;

        /** Structural inheritance declaration: {@code childType -> [ancestorType...]}; empty = no inheritance. */
        private Map<String, List<String>> inheritable = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getResolver() {
            return resolver;
        }

        public void setResolver(String resolver) {
            this.resolver = resolver;
        }

        public int getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
        }

        public Map<String, List<String>> getInheritable() {
            return inheritable;
        }

        public void setInheritable(Map<String, List<String>> inheritable) {
            this.inheritable = inheritable;
        }
    }

    /**
     * Resource-resolution (attribute-rich pre-authorization) settings (Phase 5.97). The mechanism is
     * opt-in by bean presence (an app registers an {@code AbacResourceResolver}); {@code enabled} is the
     * <strong>kill-switch</strong> — default {@code true}, and turning it off restores the pre-resolution
     * reference-based gate semantics with the beans untouched (the rollback path for a buggy or slow
     * resolver, whose failures otherwise fail closed into 403s).
     */
    public static class ResourceResolution {

        /** Kill-switch for gate-side resource resolution. Default {@code true}; off → baseline semantics. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Where the JWT subject claims live (all paths support dotted nesting). */
    public static class Subject {

        /**
         * Explicit acknowledgment that a <strong>signature-validating gateway</strong> sits in front of
         * this app. The default {@code JwtClaimsSubjectExtractor} performs <em>no signature
         * verification</em> (it decodes the forwarded token's payload and trusts it) — deployed without a
         * validating gateway that is a total authentication bypass, so the extractor stays <strong>off
         * until this is set {@code true}</strong>. While off, every request is anonymous and all checks
         * deny (fail-closed); a startup warning says so. Providing your own
         * {@code AbacSubjectExtractor} bean bypasses this toggle entirely.
         */
        private boolean trustForwardedJwt = false;

        /** Claim path for the subject id. */
        private String idClaim = "sub";

        /** Claim path for the roles array. */
        private String rolesClaim = "realm_access.roles";

        /** Claim path for the username. */
        private String usernameClaim = "preferred_username";

        /** Additional top-level claim names copied into subject attributes. */
        private List<String> attributeClaims = new ArrayList<>();

        /** Reject a token whose {@code exp} is in the past. */
        private boolean validateExpiry = true;

        public boolean isTrustForwardedJwt() {
            return trustForwardedJwt;
        }

        public void setTrustForwardedJwt(boolean trustForwardedJwt) {
            this.trustForwardedJwt = trustForwardedJwt;
        }

        public String getIdClaim() {
            return idClaim;
        }

        public void setIdClaim(String idClaim) {
            this.idClaim = idClaim;
        }

        public String getRolesClaim() {
            return rolesClaim;
        }

        public void setRolesClaim(String rolesClaim) {
            this.rolesClaim = rolesClaim;
        }

        public String getUsernameClaim() {
            return usernameClaim;
        }

        public void setUsernameClaim(String usernameClaim) {
            this.usernameClaim = usernameClaim;
        }

        public List<String> getAttributeClaims() {
            return attributeClaims;
        }

        public void setAttributeClaims(List<String> attributeClaims) {
            this.attributeClaims = attributeClaims;
        }

        public boolean isValidateExpiry() {
            return validateExpiry;
        }

        public void setValidateExpiry(boolean validateExpiry) {
            this.validateExpiry = validateExpiry;
        }
    }
}
