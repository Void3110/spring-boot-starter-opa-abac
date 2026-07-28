package dev.dmitriikonovalov.example.mcp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The Java ↔ Rego wire contract for the capability profile.
 *
 * <p>This is the seam most likely to drift silently. The profile is serialized into
 * {@code input.subject.attributes.agent_capability} and read by {@code agent_tools.rego} by exact key
 * name. Rename a field on either side and the policy sees no capability — which it correctly reads as
 * deny, so nothing breaks unsafely, but the agent is denied everything for a reason no log explains.
 * These assertions pin both halves and cross-check them against the policy file itself.
 */
class AgentCapabilityWireContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> WIRE_KEYS =
            Set.of("allowed_categories", "allowed_tools", "allowed_actions", "max_risk_tag");

    @Test
    void serializesToTheSnakeCaseKeysThePolicyReads() {
        AgentCapabilityProfile profile = new AgentCapabilityProfile(
                Set.of("READ"), Set.of("list_catalogs"), Set.of("view", "list"), "medium");

        String json = MAPPER.writeValueAsString(profile);

        assertThat(MAPPER.readTree(json).propertyNames()).containsExactlyInAnyOrderElementsOf(WIRE_KEYS);
        assertThat(json).contains("\"max_risk_tag\":\"medium\"");
        // ...and emphatically not the Java names.
        assertThat(json).doesNotContain("allowedCategories").doesNotContain("maxRiskTag");
    }

    @Test
    void roundTripsThroughTheWireShape() {
        AgentCapabilityProfile original = new AgentCapabilityProfile(
                Set.of("READ", "TAG"), Set.of("get_product"), Set.of("view"), "high");

        AgentCapabilityProfile parsed = MAPPER.readValue(
                MAPPER.writeValueAsString(original), AgentCapabilityProfile.class);

        assertThat(parsed).isEqualTo(original);
    }

    @Test // the cross-check: every key this record emits is a key the policy actually reads
    void everyWireKeyAppearsInTheToolGatePolicy() throws IOException {
        Path policy = locate("infra/opa/policies/agent_tools.rego");
        assumeThat(policy)
                .as("agent_tools.rego must be reachable from the module directory")
                .isNotNull();

        String rego = Files.readString(policy);

        for (String key : WIRE_KEYS) {
            assertThat(rego)
                    .as("agent_tools.rego must read capability key '%s'", key)
                    .contains(key);
        }
    }

    /** Walk up from the working directory until the repo-relative path resolves. */
    private static Path locate(String repoRelative) {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && candidate != null; depth++) {
            Path resolved = candidate.resolve(repoRelative);
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
            candidate = candidate.getParent();
        }
        return null;
    }
}
