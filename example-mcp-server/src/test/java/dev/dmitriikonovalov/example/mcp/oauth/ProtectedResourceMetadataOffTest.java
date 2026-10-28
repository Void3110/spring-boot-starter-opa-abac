package dev.dmitriikonovalov.example.mcp.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * U1, as served — the shipped {@code application.yml} leaves discovery off, and both document paths then
 * answer {@code 404}: no document at all rather than a guessed one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProtectedResourceMetadataOffTest {

    @LocalServerPort
    int port;

    @ParameterizedTest(name = "U1 — GET {0} with discovery off") // U1
    @ValueSource(strings = {ProtectedResourceMetadataController.ROOT_PATH, ProtectedResourceMetadataController.MCP_PATH})
    void bothPathsAnswer404WhenDiscoveryIsOff(String path) throws Exception {
        RawHttp response = RawHttp.exchange(port, "GET", path);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
    }
}
