package dev.dmitriikonovalov.opaabac.autoconfigure.mcp;

import dev.dmitriikonovalov.opaabac.mcp.authz.AsyncToolCallGate;
import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.function.BiFunction;
import org.springframework.boot.test.context.SpringBootTest;

/** I3: {@code type=ASYNC}, {@code protocol=STREAMABLE} — the gate on a real {@code POST /mcp} (ADR 0036 §4). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {McpGateTestApplication.class, AsyncProbeTool.class},
        properties = {
            "spring.ai.mcp.server.type=ASYNC",
            "spring.ai.mcp.server.protocol=STREAMABLE",
            "opa.abac.subject.trust-forwarded-jwt=true",
            "opa.abac.subject.attribute-claims=act_chain",
            "spring.ai.mcp.server.annotation-scanner.enabled=true"
        })
class AsyncStreamableToolGateIT extends PerTypeToolGateContract {

    @Override
    Class<? extends Record> specificationType() {
        return McpServerFeatures.AsyncToolSpecification.class;
    }

    @Override
    Class<? extends BiFunction<?, ?, ?>> gateType() {
        return AsyncToolCallGate.class;
    }

    @Override
    boolean stateful() {
        return true;
    }
}
