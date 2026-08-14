package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.DenyReason;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The library's audit emission point (ADR 0030 §8) — <strong>emission only</strong>, no retention story.
 *
 * <p>Everything goes to one dedicated, separately-routable SLF4J logger, <strong>{@code opa.abac.audit}
 * </strong>, named explicitly rather than derived from this class so a consumer can route it
 * independently of the library's own logs (and a test can capture it by name). Nothing is persisted:
 * read-audit retention, routing and review cadence are properly the consumer's, and every major platform
 * surveyed makes read-audit opt-in for the same reason.
 *
 * <h2>Two events</h2>
 * <ul>
 *   <li>{@code STEP_UP_CHALLENGED} — a challenge was minted. Deliberately carries <em>no</em>
 *       {@code acr}/{@code auth_time}: at challenge time the subject is precisely <em>not</em> elevated,
 *       so those fields would only ever record the state that failed.</li>
 *   <li>{@code SUPERVISED_PRODUCTION_READ} — an allowed supervised read of production content, i.e. the
 *       privileged read the second factor exists for. This is the elevation <em>in use</em>, which is
 *       the auditable fact; there is no separate "an elevation happened" event, because the library
 *       never sees the identity provider's ceremony, only tokens.</li>
 * </ul>
 *
 * <h2>Audit never affects the decision</h2>
 * Every emit path catches and drops its own exceptions. An audit bug must not become an authorization
 * outage — and the failure of a log-of-last-resort has nowhere to go but a debug line on the library's
 * own logger.
 */
public final class AbacAuditLogger {

    /** The dedicated audit channel — named, not class-derived, so it routes independently. */
    private static final Logger audit = LoggerFactory.getLogger("opa.abac.audit");

    /** This class's own logger, for the one thing it can report: that auditing itself failed. */
    private static final Logger log = LoggerFactory.getLogger(AbacAuditLogger.class);

    private AbacAuditLogger() {}

    /**
     * Emit {@code STEP_UP_CHALLENGED} — a 401 + challenge was returned to {@code subjectId}.
     *
     * @param subjectId the challenged subject, or {@code null} if the context no longer holds one
     * @param decision  the denied decision carrying the reason and the log-only coordinates
     */
    public static void stepUpChallenged(String subjectId, StepUpRequiredDecision decision) {
        try {
            DenyReason reason = decision.reason();
            audit.info("event=STEP_UP_CHALLENGED subject={} resourceType={} resourceId={} "
                            + "governingRootId={} requiredAcr={} maxAge={} reasonType={}",
                    subjectId,
                    decision.resourceType(),
                    decision.resourceId(),
                    decision.governingRootId(),
                    reason.requiredAcr(),
                    reason.maxAge(),
                    reason.type());
        } catch (RuntimeException e) {
            log.debug("audit emission failed for STEP_UP_CHALLENGED ({})", e.getClass().getSimpleName());
        }
    }

    /**
     * Emit {@code SUPERVISED_PRODUCTION_READ} — an allowed supervised read of production content.
     *
     * <p>{@code acr} and {@code auth_time} are logged <strong>verbatim</strong> from the subject's
     * attributes. Elevation is <em>implied by the allow</em> — the policy already required it — and is
     * never re-derived here: a Java copy of the LoA map or the freshness window would create a second
     * source of truth for the one number ADR 0030 Amendment 3 insists exists once.
     *
     * @param subjectId       the reading subject
     * @param attributes      the subject's attributes, read for {@code acr}/{@code auth_time}
     * @param resourceType    the resource read
     * @param resourceId      the resource's id
     * @param governingRootId the governing root whose tier made this privileged
     * @param accessPath      how the subject reached it (the role's provenance)
     */
    public static void supervisedProductionRead(
            String subjectId,
            Map<String, Object> attributes,
            String resourceType,
            String resourceId,
            String governingRootId,
            String accessPath) {
        try {
            Map<String, Object> safe = attributes == null ? Map.of() : attributes;
            audit.info("event=SUPERVISED_PRODUCTION_READ subject={} accessPath={} governingRootId={} "
                            + "resourceType={} resourceId={} acr={} authTime={}",
                    subjectId,
                    accessPath,
                    governingRootId,
                    resourceType,
                    resourceId,
                    safe.get("acr"),
                    safe.get("auth_time"));
        } catch (RuntimeException e) {
            log.debug("audit emission failed for SUPERVISED_PRODUCTION_READ ({})",
                    e.getClass().getSimpleName());
        }
    }
}
