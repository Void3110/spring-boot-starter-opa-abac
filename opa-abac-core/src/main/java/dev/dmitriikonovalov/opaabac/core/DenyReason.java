package dev.dmitriikonovalov.opaabac.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A <strong>structured</strong> reason accompanying a policy deny — the optional {@code deny_reason}
 * object of the decision envelope (ADR 0030 §6).
 *
 * <p>Today exactly one reason type exists: {@code insufficient_user_authentication}, the step-up deny.
 * The policy emits it <em>only</em> when a second factor is the <strong>sole</strong> blocker — the
 * subject is otherwise granted and no other deny fires — so a consumer may treat its presence as
 * "re-authenticating at {@link #requiredAcr()} within {@link #maxAge()} seconds turns this deny into an
 * allow". That promise is what makes the reason safe to surface as an RFC 9470 challenge; it is also
 * why a reason must never be <em>synthesized</em> by the library (during an OPA outage the promise
 * would be a lie, and the client would loop on a factor that changes nothing).
 *
 * <p>The record is typed rather than a {@code Map<String, Object>} deliberately: the fail-closed check
 * ("is this reason complete enough to act on?") then happens once, at parse time, and every consumer
 * downstream is a dumb field reader.
 *
 * <p><strong>Nullability:</strong> any field may be {@code null} when the policy emitted a partial
 * object. A partial reason is not an error — it is simply not actionable, and callers must fall back
 * to their plain deny path rather than emit a half-formed challenge.
 *
 * @param type        the reason type, e.g. {@code insufficient_user_authentication}
 * @param requiredAcr the authentication-context class the subject must reach, e.g. {@code aal2}
 * @param maxAge      the freshness window in seconds — how recent the authentication must be
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DenyReason(
        @JsonProperty("type") String type,
        @JsonProperty("required_acr") String requiredAcr,
        @JsonProperty("max_age") Integer maxAge) {

    /** The one reason type this library knows: a fresh second factor is required (ADR 0030 §6). */
    public static final String INSUFFICIENT_USER_AUTHENTICATION = "insufficient_user_authentication";

    /**
     * Whether every field needed to act on this reason is present.
     *
     * <p>A challenge built from an incomplete reason is worse than none: a {@code WWW-Authenticate}
     * without {@code max_age} lets a client re-authenticate against a still-valid session, receive the
     * same stale {@code auth_time}, and be challenged again — ADR 0030 §7's infinite loop.
     *
     * @return {@code true} if type, required ACR and max age are all present
     */
    public boolean isComplete() {
        return type != null && requiredAcr != null && maxAge != null;
    }
}
