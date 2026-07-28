package dev.dmitriikonovalov.example.mcp.identity;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the delegation chain from a plain custom claim carrying <strong>RFC 8693 {@code act}
 * semantics</strong>, minted by a stock Keycloak protocol mapper.
 *
 * <p>Two wire shapes are accepted and normalized to the same ordered chain, because the semantics — not
 * the encoding — are what the policy cares about:
 *
 * <pre>
 *   nested (RFC 8693):   {"sub": "agent-a", "act": {"sub": "agent-b"}}
 *   flattened (demo):    ["agent-a", "agent-b"]   or   [{"sub": "agent-a"}, {"sub": "agent-b"}]
 * </pre>
 *
 * Both yield {@code chain = [agent-a, agent-b]} — <strong>nearest actor first</strong>, so
 * {@code agent-a} is the one actually making this call and {@code agent-b} delegated to it. This is why
 * the seam exists at all: when a stock release does emit a real {@code act}, only this class changes.
 *
 * <h2>Fail-closed, and iteratively</h2>
 * Every malformed shape throws {@link DelegationChainException} — which callers map to <em>deny</em>,
 * never to a principal-only fallback. The nested form is walked <strong>iteratively</strong>, not
 * recursively: this repo has already been bitten once by an untrusted-input structure that recursed into
 * a {@code StackOverflowError} and escaped a {@code catch (Exception)} fail-closed handler, and a deeply
 * nested {@code act} is exactly that shape. The size cap is checked <em>before</em> any traversal.
 */
public class ClaimDelegationChainExtractor implements DelegationChainExtractor {

    /**
     * Legal actor-id characters. Deliberately an explicit ASCII class rather than {@code \w}: this is an
     * identity that will be serialized into a policy input and a log line, and an explicit class cannot
     * silently widen to Unicode if the pattern is ever recompiled with different flags.
     */
    private static final Pattern ACTOR_ID = Pattern.compile("[A-Za-z0-9._:@-]{1,128}");

    private static final String SUB = "sub";
    private static final String ACT = "act";

    private static final Logger log = LoggerFactory.getLogger(ClaimDelegationChainExtractor.class);

    private final ObjectMapper objectMapper;
    private final IdentityProperties properties;

    public ClaimDelegationChainExtractor(ObjectMapper objectMapper, IdentityProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public DelegationChain extract(AbacContext.Subject subject) {
        String principal = subject == null ? null : subject.id();
        if (principal == null || principal.isBlank()) {
            // Defense in depth: the starter's extractor already refuses to build a subject without a
            // sub. With no principal there is no honest evaluation to fall back to, so this denies.
            throw new DelegationChainException("The caller has no principal.");
        }

        Object claim = subject.attributes().get(properties.getActorClaim());
        if (claim == null) {
            return DelegationChain.ofPrincipal(principal);
        }

        requireWithinSizeCap(claim);
        List<String> chain = readChain(claim);
        requireNoCycle(principal, chain);

        log.debug("Delegation chain resolved: principal={} depth={}", principal, chain.size());
        return new DelegationChain(principal, chain.get(0), chain);
    }

    /** Reject an oversized claim before walking it — cheap, and it bounds everything downstream. */
    private void requireWithinSizeCap(Object claim) {
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(claim);
        } catch (JacksonException _) {
            throw new DelegationChainException("The delegation claim is not serializable.");
        }
        if (serialized.length() > properties.getMaxClaimLength()) {
            throw new DelegationChainException("The delegation claim exceeds the configured size limit.");
        }
    }

    /** Normalize either wire shape into an ordered, nearest-first list of actor ids. */
    private List<String> readChain(Object claim) {
        List<String> chain = claim instanceof List<?> flattened
                ? readFlattened(flattened)
                : readNested(claim);
        if (chain.isEmpty()) {
            // A claim that is PRESENT but yields no actor is malformed, not "human": whoever minted it
            // meant to say an agent was involved. Absent is the only shape that means human.
            throw new DelegationChainException("The delegation claim names no actor.");
        }
        return List.copyOf(chain);
    }

    private List<String> readFlattened(List<?> elements) {
        requireDepthWithinLimit(elements.size());
        List<String> chain = new ArrayList<>(elements.size());
        for (Object element : elements) {
            chain.add(actorIdOf(element));
        }
        return chain;
    }

    /**
     * Walk {@code {"sub": …, "act": {…}}} outward-in. Iterative on purpose (see the class javadoc), and
     * the depth limit is enforced <em>during</em> the walk so a deep claim is rejected as it is read
     * rather than after it has been fully materialized.
     */
    private List<String> readNested(Object claim) {
        if (!(claim instanceof Map<?, ?>)) {
            throw new DelegationChainException("The delegation claim has the wrong type.");
        }
        List<String> chain = new ArrayList<>();
        Object current = claim;
        while (current != null) {
            if (!(current instanceof Map<?, ?> node)) {
                throw new DelegationChainException("The delegation claim has a malformed entry.");
            }
            chain.add(actorIdOf(node));
            requireDepthWithinLimit(chain.size());
            current = node.get(ACT);
        }
        return chain;
    }

    /** One actor id, from either a bare string or a {@code {"sub": …}} object. */
    private static String actorIdOf(Object element) {
        Object raw = element instanceof Map<?, ?> node ? node.get(SUB) : element;
        if (!(raw instanceof String id)) {
            throw new DelegationChainException("The delegation claim has a non-string actor id.");
        }
        if (id.isBlank() || !ACTOR_ID.matcher(id).matches()) {
            throw new DelegationChainException("The delegation claim has an illegal actor id.");
        }
        return id;
    }

    private void requireDepthWithinLimit(int depth) {
        if (depth > properties.getMaxChainDepth()) {
            throw new DelegationChainException("The delegation chain is deeper than the configured limit.");
        }
    }

    /**
     * A repeated actor, or an actor equal to the principal, is a loop. Loops are how a chain gets padded
     * to look longer than it is, and an agent asserting it acts for itself is not a delegation at all.
     */
    private static void requireNoCycle(String principal, List<String> chain) {
        Set<String> seen = new LinkedHashSet<>();
        for (String actor : chain) {
            if (actor.equals(principal) || !seen.add(actor)) {
                throw new DelegationChainException("The delegation chain is cyclic.");
            }
        }
    }
}
