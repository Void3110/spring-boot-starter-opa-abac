package dev.dmitriikonovalov.opaabac.autoconfigure.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The synchronous probe tool — for {@code spring.ai.mcp.server.type=SYNC}. */
@Configuration(proxyBeanMethods = false)
public class SyncProbeTool {

    @Bean
    Probe probe() {
        return new Probe();
    }

    public static class Probe {
        @McpTool(name = McpGateTestApplication.TOOL, description = "A probe tool that counts its invocations")
        public String probe() {
            McpGateTestApplication.BODY_RUNS.incrementAndGet();
            return "probed";
        }
    }
}
