package dev.dmitriikonovalov.opaabac.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * What every supported server configuration must show over a real {@code POST /mcp} (ADR 0036 §4): the
 * caller's identity reaches the gate's decision, a policy deny is the tool-gate's advisory with the body
 * never run, and a policy allow runs the body once. A configuration whose subclass cannot pass this is not
 * shipped as supported.
 */
abstract class PerTypeToolGateContract extends AbstractMcpToolGateIT {

    @Autowired
    ApplicationContext context;

    /** The specification record this configuration's server is built from. */
    abstract Class<? extends Record> specificationType();

    /** The gate that must sit on that record's handler. */
    abstract Class<? extends BiFunction<?, ?, ?>> gateType();

    @Test // non-vacuity: the server really is built from THIS configuration's type, and it carries its gate
    void theProbeToolIsServedAsThisConfigurationsTypeBehindItsGate() throws Exception {
        List<Object> probes = new ArrayList<>();
        for (Object bean : context.getBeansOfType(List.class).values()) {
            for (Object element : (List<?>) bean) {
                if (specificationType().isInstance(element)) {
                    probes.add(element);
                }
            }
        }
        assertThat(probes).as("one " + specificationType().getName() + " for the probe tool").hasSize(1);
        Object handler = specificationType().getMethod("callHandler").invoke(probes.get(0));
        assertThat(handler).isInstanceOf(gateType());
    }

    // --- the assertions every configuration must satisfy ------------------------------------------

    @Test
    void aPolicyDenyIsTheToolGatesAdvisoryAndTheBodyNeverRuns() throws Exception {
        OPA_ALLOWS.set(false);

        JsonNode result = callTool();

        assertThat(result.path("isError").asBoolean()).as("a denied call is an error result").isTrue();
        assertThat(result.path("_meta").path("layer").asString()).isEqualTo("tool-gate");
        assertThat(result.path("_meta").path("code").asString()).isEqualTo("tool-gate-denied");
        assertThat(McpGateTestApplication.BODY_RUNS).as("the tool body never ran").hasValue(0);
        assertTheGateDecidedWithTheCallersIdentity();
    }

    @Test
    void aPolicyAllowReachesTheBody() throws Exception {
        OPA_ALLOWS.set(true);

        JsonNode result = callTool();

        assertThat(result.path("isError").asBoolean()).as("an allowed call succeeds: " + result).isFalse();
        assertThat(result.toString()).contains("probed");
        assertThat(McpGateTestApplication.BODY_RUNS).as("the tool body ran exactly once").hasValue(1);
        assertTheGateDecidedWithTheCallersIdentity();
    }

    private void assertTheGateDecidedWithTheCallersIdentity() {
        assertThat(OPA_INPUTS).as("the gate asked the policy exactly once").hasSize(1);
        JsonNode input = json.readTree(OPA_INPUTS.get(0)).path("input");
        assertThat(input.path("subject").path("id").asString())
                .as("the identity was visible on the thread where the gate decided")
                .isEqualTo(CALLER);
        assertThat(input.path("resource").path("type").asString()).isEqualTo("tool");
        assertThat(input.path("resource").path("id").asString()).isEqualTo(McpGateTestApplication.TOOL);
    }

}
