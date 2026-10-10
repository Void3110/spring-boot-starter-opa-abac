package dev.dmitriikonovalov.opaabac.autoconfigure.mcp;

import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallGate;
import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.function.BiFunction;
import org.springframework.boot.test.context.SpringBootTest;

/** I2: {@code type=SYNC}, {@code protocol=STREAMABLE} — the gate on a real {@code POST /mcp} (ADR 0036 §4). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {McpGateTestApplication.class, SyncProbeTool.class},
        properties = {
            "spring.ai.mcp.server.type=SYNC",
            "spring.ai.mcp.server.protocol=STREAMABLE",
            "opa.abac.subject.trust-forwarded-jwt=true",
            "opa.abac.subject.attribute-claims=act_chain",
            "spring.ai.mcp.server.annotation-scanner.enabled=true"
        })
class SyncStreamableToolGateIT extends PerTypeToolGateContract {

    @Override
    Class<? extends Record> specificationType() {
        return McpServerFeatures.SyncToolSpecification.class;
    }

    @Override
    Class<? extends BiFunction<?, ?, ?>> gateType() {
        return ToolCallGate.class;
    }

    @Override
    boolean stateful() {
        return true;
    }
}
