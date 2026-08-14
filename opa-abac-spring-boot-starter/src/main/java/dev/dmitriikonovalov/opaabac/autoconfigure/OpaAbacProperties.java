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

    private Audit audit = new Audit();

    /** Partial-evaluation (list data-filtering) settings. */
    @NestedConfigurationProperty
    private PartialEval partialEval = new PartialEval();

    /** N-level hierarchical authorization settings (opt-in, default-off). */
    @NestedConfigurationProperty
    private Hierarchy hierarchy = new Hierarchy();

    /** Resource-resolution (attribute-rich pre-authorization) settings. */
    @NestedConfigurationProperty
    private ResourceResolution resourceResolution = new ResourceResolution();

    /** Action-enrichment (affordance metadata) settings. */
    @NestedConfigurationProperty
    private ActionEnrichment actionEnrichment = new ActionEnrichment();

    /** Cross-service HTTP resilience (retry / backoff / circuit-break) settings (Slice B3). */
    @NestedConfigurationProperty
    private Resilience resilience = new Resilience();

    /** Request-scoped resolution memoization settings (Slice 7.3, ADR 0023). */
    @NestedConfigurationProperty
    private ResolveMemo resolveMemo = new ResolveMemo();

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

    public Audit getAudit() {
        return audit;
    }

    public void setAudit(Audit audit) {
        this.audit = audit;
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

    public ActionEnrichment getActionEnrichment() {
        return actionEnrichment;
    }

    public void setActionEnrichment(ActionEnrichment actionEnrichment) {
        this.actionEnrichment = actionEnrichment;
    }

    public Resilience getResilience() {
        return resilience;
    }

    public void setResilience(Resilience resilience) {
        this.resilience = resilience;
    }

    public ResolveMemo getResolveMemo() {
        return resolveMemo;
    }

    public void setResolveMemo(ResolveMemo resolveMemo) {
        this.resolveMemo = resolveMemo;
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

    /**
     * Request-scoped resolution memoization (Slice 7.3, ADR 0023). One flag governs <em>both</em> memo
     * decorators (role + ancestor — one knob, one axis: request-scoped resolution memoization).
     * {@code false} restores per-call resolution — snapshot-freshness semantics, <em>not</em> pre-7.3
     * call counts (the enrichment advice's batch collection, ADR 0024, is unconditional code).
     */
    public static class ResolveMemo {

        /**
         * Kill-switch for the request-scoped memos. Default {@code true} (the memo is the fix for a
         * measured defect); off → every resolve call reaches the supplier/resolver (per-call
         * freshness at the measured per-request amplification cost).
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Action-enrichment (affordance metadata) settings. {@code enabled} is a true kill-switch: when off,
     * the {@code ActionEnrichmentAdvice} bean is not registered <em>and</em> the list-path cache
     * write-through is dormant, so an {@code Enrichable} DTO serializes without {@code _actions} —
     * byte-identical to the pre-Phase-6 behavior.
     */
    public static class ActionEnrichment {

        /** Kill-switch for affordance enrichment. Default {@code true}; off → no {@code _actions}, no write-through. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Cross-service HTTP resilience (Slice B3, ADR 0017) — a uniform retry/backoff/circuit-break posture
     * over the three cross-service HTTP edges (OPA, resolve, tag). "Uniform" is the <em>config shape</em> and
     * the <em>fail-closed contract</em>, NOT the numbers: each edge keeps its own asymmetric budget.
     *
     * <pre>
     * opa:
     *   abac:
     *     resilience:
     *       enabled: true            # master kill-switch; off ⇒ byte-identical to pre-B3
     *       opa:                     # the gate, every request, local sidecar
     *         enabled: true
     *         max-retries: 1
     *         backoff: 50ms
     *         ceiling: 2500ms
     *         breaker: {failure-threshold: 5, open-duration: 10s, half-open-probes: 1}
     *       resolve: {max-retries: 2, ceiling: 6s, ...}   # cross-service hop
     *       tag:     {max-retries: 2, ceiling: 6s, ...}   # cross-service hop
     * </pre>
     *
     * <p>Both the master {@link #enabled} and an {@link Edge}'s own {@code enabled} are kill-switches: with
     * either off for an edge, that edge makes a single unguarded call, fail-closed exactly as pre-B3 (ADR
     * 0017 §9). The switch governs retry/breaker only — never the fail-closed contract.
     */
    public static class Resilience {

        /** Master kill-switch for B3 resilience. Default {@code true}; off ⇒ all three edges run one-shot. */
        private boolean enabled = true;

        /** OPA gate budget — every request, local sidecar: a small budget (failure ≈ a restart blip). */
        @NestedConfigurationProperty
        private Edge opa = new Edge(1, Duration.ofMillis(50), Duration.ofMillis(2500));

        /** Role-resolve budget — a cross-service hop with real transient weather: a larger budget. */
        @NestedConfigurationProperty
        private Edge resolve = new Edge(2, Duration.ofMillis(50), Duration.ofSeconds(6));

        /** Tag-definitions budget — same cross-service profile as resolve. */
        @NestedConfigurationProperty
        private Edge tag = new Edge(2, Duration.ofMillis(50), Duration.ofSeconds(6));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Edge getOpa() {
            return opa;
        }

        public void setOpa(Edge opa) {
            this.opa = opa;
        }

        public Edge getResolve() {
            return resolve;
        }

        public void setResolve(Edge resolve) {
            this.resolve = resolve;
        }

        public Edge getTag() {
            return tag;
        }

        public void setTag(Edge tag) {
            this.tag = tag;
        }
    }

    /**
     * One cross-service HTTP edge's resilience budget — the per-edge retry/backoff/ceiling + a
     * {@link Breaker}. {@code enabled} is the per-edge kill-switch. {@link #toConfig(boolean)} folds the
     * master switch in and produces the immutable
     * {@link dev.dmitriikonovalov.opaabac.security.resilience.ResilienceConfig} the {@code CallGuard} reads.
     */
    public static class Edge {

        /** Per-edge kill-switch. Default {@code true}; off (or master off) ⇒ a single unguarded call. */
        private boolean enabled = true;

        /** Retries after the first attempt (total attempts = {@code maxRetries + 1}). */
        private int maxRetries;

        /** Base exponential-backoff interval; full jitter is applied on top. */
        private Duration backoff;

        /** Named, configurable upper bound on total time spent across all attempts. */
        private Duration ceiling;

        /** Circuit-breaker parameters for this edge. */
        @NestedConfigurationProperty
        private Breaker breaker = new Breaker();

        public Edge() {
        }

        Edge(int maxRetries, Duration backoff, Duration ceiling) {
            this.maxRetries = maxRetries;
            this.backoff = backoff;
            this.ceiling = ceiling;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Duration getBackoff() {
            return backoff;
        }

        public void setBackoff(Duration backoff) {
            this.backoff = backoff;
        }

        public Duration getCeiling() {
            return ceiling;
        }

        public void setCeiling(Duration ceiling) {
            this.ceiling = ceiling;
        }

        public Breaker getBreaker() {
            return breaker;
        }

        public void setBreaker(Breaker breaker) {
            this.breaker = breaker;
        }

        /**
         * Build the immutable {@code ResilienceConfig} a {@code CallGuard} consumes. The edge is enabled only
         * when <em>both</em> the master switch and this edge's own switch are on (ADR 0017 §9).
         */
        public dev.dmitriikonovalov.opaabac.security.resilience.ResilienceConfig toConfig(boolean masterEnabled) {
            return new dev.dmitriikonovalov.opaabac.security.resilience.ResilienceConfig(
                    masterEnabled && enabled,
                    maxRetries,
                    backoff,
                    ceiling,
                    breaker.getFailureThreshold(),
                    breaker.getOpenDuration(),
                    breaker.getHalfOpenProbes());
        }
    }

    /** Circuit-breaker parameters for one edge — latency/load only, never a decision input (ADR 0017 §5). */
    public static class Breaker {

        /** Consecutive failures that open the breaker. */
        private int failureThreshold = 5;

        /** How long the breaker stays open before a half-open probe. */
        private Duration openDuration = Duration.ofSeconds(10);

        /** Permitted probe calls in the half-open state. */
        private int halfOpenProbes = 1;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }

        public int getHalfOpenProbes() {
            return halfOpenProbes;
        }

        public void setHalfOpenProbes(int halfOpenProbes) {
            this.halfOpenProbes = halfOpenProbes;
        }
    }

    /** The audit channel's opt-in triggers (ADR 0030 §8 Amendment 7). */
    public static class Audit {

        private PrivilegedRead privilegedRead = new PrivilegedRead();

        public PrivilegedRead getPrivilegedRead() {
            return privilegedRead;
        }

        public void setPrivilegedRead(PrivilegedRead privilegedRead) {
            this.privilegedRead = privilegedRead;
        }

        /**
         * Which allowed reads are privileged enough to emit {@code PRIVILEGED_READ} on the
         * {@code opa.abac.audit} channel — in <strong>your</strong> vocabulary, because only your domain
         * can say what "an oversight role read sensitive-tier content" means.
         *
         * <p><strong>Unset means silent</strong>: with no {@code provenance} configured the event is
         * never emitted. The other audit event, {@code STEP_UP_CHALLENGED}, is vocabulary-free and needs
         * no configuration.
         */
        public static class PrivilegedRead {

            /** The {@code role_definition.attributes.provenance} stamp marking the privileged path. */
            private String provenance;

            /**
             * The governing root's attribute naming the tier. Defaulted, but the DEFAULT ITSELF is not
             * "configured": {@link #isAnySet()} tracks explicit assignment so a block naming only this
             * knob still counts as half-configured and fails startup.
             */
            private String rootAttribute = "env";

            /** Whether the adopter explicitly set {@link #rootAttribute} (as opposed to the default). */
            private boolean rootAttributeSet;

            /** The tier values that make a read privileged (scalar or array attribute). */
            private List<String> rootValues = new ArrayList<>();

            public String getProvenance() {
                return provenance;
            }

            public void setProvenance(String provenance) {
                this.provenance = provenance;
            }

            public String getRootAttribute() {
                return rootAttribute;
            }

            public void setRootAttribute(String rootAttribute) {
                this.rootAttribute = rootAttribute;
                this.rootAttributeSet = true;
            }

            /**
             * Whether ANY of the three knobs was set — the "is this block present at all?" question the
             * starter asks. Entirely absent means the privileged-read event is off; anything else is a
             * configuration the record must validate, so a typo fails startup instead of silently
             * disabling an audit control.
             */
            public boolean isAnySet() {
                return (provenance != null && !provenance.isBlank())
                        || rootAttributeSet
                        || !rootValues.isEmpty();
            }

            public List<String> getRootValues() {
                return rootValues;
            }

            public void setRootValues(List<String> rootValues) {
                this.rootValues = rootValues;
            }
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
