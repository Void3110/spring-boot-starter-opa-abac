package dev.dmitriikonovalov.opaabac.autoconfigure.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/** The reactive probe tool — for {@code spring.ai.mcp.server.type=ASYNC}; its body runs at subscription. */
@Configuration(proxyBeanMethods = false)
public class AsyncProbeTool {

    @Bean
    Probe probe() {
        return new Probe();
    }

    public static class Probe {
        @McpTool(name = McpGateTestApplication.TOOL, description = "A reactive probe tool that counts its invocations")
        public Mono<String> probe() {
            return Mono.fromSupplier(() -> {
                McpGateTestApplication.BODY_RUNS.incrementAndGet();
                return "probed";
            });
        }
    }
}
