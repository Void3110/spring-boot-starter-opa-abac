package dev.dmitriikonovalov.opaabac.autoconfigure.mcp;

import dev.dmitriikonovalov.opaabac.mcp.authz.StatelessAsyncToolCallGate;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import java.util.function.BiFunction;
import org.springframework.boot.test.context.SpringBootTest;

/** I5: {@code type=ASYNC}, {@code protocol=STATELESS} — the gate on a real {@code POST /mcp} (ADR 0036 §4). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {McpGateTestApplication.class, AsyncProbeTool.class},
        properties = {
            "spring.ai.mcp.server.type=ASYNC",
            "spring.ai.mcp.server.protocol=STATELESS",
            "opa.abac.subject.trust-forwarded-jwt=true",
            "opa.abac.subject.attribute-claims=act_chain",
            "spring.ai.mcp.server.annotation-scanner.enabled=true"
        })
class AsyncStatelessToolGateIT extends PerTypeToolGateContract {

    @Override
    Class<? extends Record> specificationType() {
        return McpStatelessServerFeatures.AsyncToolSpecification.class;
    }

    @Override
    Class<? extends BiFunction<?, ?, ?>> gateType() {
        return StatelessAsyncToolCallGate.class;
    }

    @Override
    boolean stateful() {
        return false;
    }
}
