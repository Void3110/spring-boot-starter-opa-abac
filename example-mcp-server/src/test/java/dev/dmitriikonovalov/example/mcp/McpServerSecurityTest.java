package dev.dmitriikonovalov.example.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.security.AbacFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

/**
 * The transport-level check under the tool-gate: an anonymous caller cannot reach the tool surface at all.
 *
 * <p>This is deliberately <em>not</em> the authorization this slice is about — it only establishes that
 * there is a principal. It is tested because {@link dev.dmitriikonovalov.example.mcp.config.SecurityConfig}
 * branches on whether the starter contributed an {@link AbacFilter}, and the branch that runs without one
 * serves on gateway trust. Asserting which branch the real context takes is the difference between "the
 * surface is authenticated" and "we assumed it was".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerSecurityTest {

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void thisServerRunsTheAuthenticatedBranchOfTheChain() {
        assertThat(context.getBeanNamesForType(AbacFilter.class))
                .as("the starter must contribute an AbacFilter, or the chain falls back to gateway trust")
                .isNotEmpty();
    }

    @Test
    void rejectsAnAnonymousCallToTheToolSurface() throws Exception {
        HttpResponse<String> response = get("/mcp");

        assertThat(response.statusCode()).isIn(401, 403);
    }

    @Test
    void rejectsAnAnonymousCallToTheActuatorBeyondHealth() throws Exception {
        HttpResponse<String> response = get("/actuator/env");

        assertThat(response.statusCode()).isIn(401, 403, 404);
    }

    @Test
    void leavesHealthOpenForTheRigsReadinessWait() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
