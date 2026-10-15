package dev.dmitriikonovalov.opaabac.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.PerTypePolicyPathResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The policy-path decorator: {@code tool} to the tool-gate document, everything else to the delegate. */
class ToolPolicyPathResolverTest {

    private final ToolAuthorizationProperties properties = new ToolAuthorizationProperties();
    private final PerTypePolicyPathResolver application = new PerTypePolicyPathResolver("abac");
    private final ToolPolicyPathResolver resolver = new ToolPolicyPathResolver(application, properties);

    private static AbacContext contextOf(String type) {
        return new AbacContext(
                new AbacContext.Subject("alice", List.of(), Map.of()),
                "view",
                new AbacContext.Resource(type, "r-1", Map.of(), List.of()),
                null,
                Map.of());
    }

    @Test
    void routesTheToolTypeToTheConfiguredPackagePath() {
        assertThat(resolver.resolve(contextOf("tool"))).isEqualTo("agent_tools");

        properties.setPolicyPath("x/y");
        assertThat(resolver.resolve(contextOf("tool"))).isEqualTo("x/y");
    }

    @Test // the correction over the example's resolver: a non-tool decision keeps the application's path
    void leavesEveryOtherTypeToTheApplicationsResolver() {
        assertThat(resolver.resolve(contextOf("catalog")))
                .isEqualTo(application.resolve(contextOf("catalog")))
                .isEqualTo("abac/catalog");
    }
}
