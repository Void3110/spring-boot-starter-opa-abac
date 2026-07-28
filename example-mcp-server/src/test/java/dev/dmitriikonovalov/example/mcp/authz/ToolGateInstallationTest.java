package dev.dmitriikonovalov.example.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.mcp.tool.ToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The gate is actually in the invocation path — asserted against the <strong>real</strong> application
 * context, not inferred from configuration.
 *
 * <p>This is the test that would fail if a Spring AI upgrade changed how tool specifications are
 * produced, or if someone added a tool that slipped past the wrapping. Everything else about the PEP
 * could be perfect and still be bypassed if the handler the MCP server invokes were the raw one, so
 * "every advertised tool's call handler is a {@link ToolCallGate}" is the invariant worth pinning.
 */
@SpringBootTest
class ToolGateInstallationTest {

    /**
     * Injected <strong>by name</strong>: the context holds more than one specification list
     * ({@code toolSpecs} from the annotation scanner and {@code syncTools} downstream of it). Asserting
     * over all of them is the stronger claim — whichever list the server ends up invoking, it is gated,
     * and a future Spring AI release that adds another one fails this test rather than opening a hole.
     */
    @Autowired
    Map<String, List<SyncToolSpecification>> specificationLists;

    @Autowired
    ToolRegistry registry;

    @Test
    void everyAdvertisedToolsHandlerIsGatedInEveryList() {
        assertThat(specificationLists).as("the scanner must have produced a tool list").isNotEmpty();

        // No list may contain an ungated handler. Empty lists are included deliberately — there is
        // nothing to gate in them, and the assertion below is what proves the surface is not empty.
        specificationLists.forEach((beanName, specifications) -> {
            for (SyncToolSpecification specification : specifications) {
                assertThat(specification.callHandler())
                        .as("call handler for '%s' in bean '%s'", specification.tool().name(), beanName)
                        .isInstanceOf(ToolCallGate.class);
            }
        });
    }

    @Test // the whole declared surface really is advertised, and gating did not alter it
    void theFullSurfaceIsAdvertisedAndUnchangedByGating() {
        List<String> advertised = specificationLists.values().stream()
                .flatMap(List::stream)
                .map(specification -> specification.tool().name())
                .toList();

        assertThat(advertised).containsExactlyInAnyOrderElementsOf(registry.names());
    }
}
