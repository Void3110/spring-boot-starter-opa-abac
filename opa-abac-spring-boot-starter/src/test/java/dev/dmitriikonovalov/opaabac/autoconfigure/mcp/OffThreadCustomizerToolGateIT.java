package dev.dmitriikonovalov.opaabac.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;

/**
 * I6 — the customizer trap (ADR 0036 §13, measured 2026-10-09): the synchronous stateful server reaches the
 * request thread only through Spring AI's servlet customizer, which sets {@code immediateExecution(true)}.
 * That customizer is not {@code @ConditionalOnMissingBean}, and the server injects a single one — so an
 * application's {@code @Primary} customizer <em>replaces</em> it, and one that does not set
 * {@code immediateExecution(true)} moves every handler to {@code boundedElastic}, where no caller is bound.
 *
 * <p>The outcome this pins is the fail-closed one: every call is denied {@code tool-gate-unauthenticated},
 * the body never runs and the policy is never asked — never an allowed call without an identity.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            McpGateTestApplication.class,
            SyncProbeTool.class,
            OffThreadCustomizerToolGateIT.OffThreadCustomizer.class
        },
        properties = {
            "spring.ai.mcp.server.type=SYNC",
            "spring.ai.mcp.server.protocol=STREAMABLE",
            "opa.abac.subject.trust-forwarded-jwt=true",
            "opa.abac.subject.attribute-claims=act_chain",
            "spring.ai.mcp.server.annotation-scanner.enabled=true"
        })
class OffThreadCustomizerToolGateIT extends AbstractMcpToolGateIT {

    @Override
    boolean stateful() {
        return true;
    }

    @Configuration(proxyBeanMethods = false)
    static class OffThreadCustomizer {
        /** An application customizer that sets something else and leaves immediate execution off. */
        @Bean
        @Primary
        McpSyncServerCustomizer applicationCustomizer() {
            return spec -> spec.instructions("an application customizer that forgot immediateExecution");
        }
    }

    @Test
    void everyCallIsDeniedUnauthenticatedWhenHandlersRunOffTheRequestThread() throws Exception {
        OPA_ALLOWS.set(true); // the policy WOULD allow — so only the missing identity can deny

        JsonNode result = callTool();

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("_meta").path("layer").asString()).isEqualTo("tool-gate");
        assertThat(result.path("_meta").path("code").asString()).isEqualTo("tool-gate-unauthenticated");
        assertThat(McpGateTestApplication.BODY_RUNS).as("the tool body never ran").hasValue(0);
        assertThat(OPA_INPUTS).as("with no caller on the thread the policy is never asked").isEmpty();
    }
}
