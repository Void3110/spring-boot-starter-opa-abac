package dev.dmitriikonovalov.example.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * U7, the falsifier half: the shipped {@code application.yml} lists the agent clients, so the startup wiring
 * check holds the example to copying {@code azp}. Without it the guard could never fire, and a listed agent
 * client whose actor mapper went missing would be read as an ordinary human call. The other half — the
 * shipped YAML starts the context — is every {@code @SpringBootTest} in this module.
 */
class HostAgentWiringTest {

    @Test
    void dropAzpFromTheCopiedClaimsAndTheServerRefusesToStart() {
        // Command-line arguments, not builder.properties(): those are DEFAULT properties, the lowest
        // precedence, and application.yml's list would win over them (measured — the server started).
        SpringApplicationBuilder app = new SpringApplicationBuilder(McpServerApplication.class);

        // Un-wrapped: the check is a SmartInitializingSingleton, which throws straight out of refresh().
        assertThatThrownBy(() -> app.run("--server.port=0", "--opa.abac.subject.attribute-claims=act_chain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("opa.abac.mcp.identity.agent-clients=[catalog-agent-readonly, "
                        + "catalog-agent-overreach, catalog-agent-revoked, catalog-mcp-host]")
                .hasMessageContaining("needs the 'azp' claim");
    }
}
