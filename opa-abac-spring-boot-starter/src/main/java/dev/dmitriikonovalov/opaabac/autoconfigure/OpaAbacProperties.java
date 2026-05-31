package dev.dmitriikonovalov.opaabac.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 *     verify-signature: false
 *     subject:
 *       id-claim: sub
 *       roles-claim: realm_access.roles
 *       username-claim: preferred_username
 *       attribute-claims: []
 *       validate-expiry: true
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

    /**
     * Reserved: re-verify the JWT signature in the app. <strong>Not implemented in this slice</strong> —
     * the app trusts a validating gateway and does structural + {@code exp} checks only. Setting this to
     * {@code true} has no effect yet.
     */
    private boolean verifySignature = false;

    /** How the JWT subject is read from claims. */
    @NestedConfigurationProperty
    private Subject subject = new Subject();

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

    public boolean isVerifySignature() {
        return verifySignature;
    }

    public void setVerifySignature(boolean verifySignature) {
        this.verifySignature = verifySignature;
    }

    public Subject getSubject() {
        return subject;
    }

    public void setSubject(Subject subject) {
        this.subject = subject;
    }

    /** Where the JWT subject claims live (all paths support dotted nesting). */
    public static class Subject {

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
