package dev.dmitriikonovalov.example.mcp.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The demo capability source: profiles configured under {@code example.mcp.agents}.
 *
 * <h2>An unknown actor is authoritative-empty, not an error</h2>
 * An agent nobody configured has no capability — a real, final answer that denies every tool. It is
 * emphatically <em>not</em> an outage: treating "I have never heard of this agent" as "the registry is
 * down" would make every typo look like an incident, and treating it as "no restriction" would be the
 * vulnerability this whole slice exists to prevent.
 *
 * <h2>Why a config source still converts failures into the outage signal</h2>
 * A local config source has no realistic outage mode, so in practice this never throws. The conversion
 * is here anyway because it is the <em>contract</em>, not the implementation, that callers depend on:
 * a deployment that swaps in a registry-backed supplier must not have to change a single caller for
 * the fail-closed behaviour to hold. Anything unexpected from the source becomes
 * {@link AgentCapabilityUnavailableException} rather than a silent {@code empty()}, because a silent
 * empty would report "this agent may do nothing" when the truth is "we do not know".
 */
public class ConfigAgentCapabilitySupplier implements AgentCapabilitySupplier {

    private static final Logger log = LoggerFactory.getLogger(ConfigAgentCapabilitySupplier.class);

    private final AgentCapabilityProperties properties;

    public ConfigAgentCapabilitySupplier(AgentCapabilityProperties properties) {
        this.properties = properties;
    }

    @Override
    public AgentCapabilityProfile lookup(String actorId) {
        Map<String, AgentCapabilityProfile> index;
        try {
            index = indexByActor();
        } catch (RuntimeException e) {
            throw new AgentCapabilityUnavailableException(
                    "The agent capability source could not be read.", e);
        }

        AgentCapabilityProfile profile = index.get(actorId);
        if (profile == null) {
            log.warn("No capability profile configured for actor '{}' — denying every tool "
                    + "(authoritative-empty, not an outage)", actorId);
            return AgentCapabilityProfile.empty();
        }
        return profile;
    }

    private Map<String, AgentCapabilityProfile> indexByActor() {
        Map<String, AgentCapabilityProfile> index = new LinkedHashMap<>();
        for (AgentCapabilityProperties.Profile configured : properties.getProfiles()) {
            String actorId = configured.getActorId();
            if (actorId == null || actorId.isBlank()) {
                throw new IllegalStateException("An agent capability profile declares no actor-id.");
            }
            index.put(actorId, configured.toProfile());
        }
        return index;
    }
}
