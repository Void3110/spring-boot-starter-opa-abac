package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.example.catalog.config.SupervisedScopeClient;
import dev.dmitriikonovalov.opaabac.security.resilience.CallGuard;
import dev.dmitriikonovalov.opaabac.security.resilience.Resilience4jCallGuard;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The <b>wiring</b> half of T4: with the user-service edge configured ({@code catalog.role-source=http}),
 * the {@link SupervisedScopeClient} bean exists, reads its <b>own</b>
 * {@code catalog.user-service.supervised-base-url} property — which <b>defaults to the shared</b>
 * {@code catalog.user-service.base-url}, so the shipped rig is unchanged — and runs through a
 * <b>separate breaker</b> from the resolve edge.
 *
 * <p>Three things this pins that a plain unit test cannot:
 * <ol>
 *   <li>the {@code role-source=http} context <em>boots</em> with the new bean and the nested property
 *       default actually resolves (a placeholder typo would only ever surface on the rig);</li>
 *   <li>the dedicated property really is the one the client reads — E8 fault-injects <em>only</em> this
 *       edge by repointing it, which is impossible if the client silently read the shared URL;</li>
 *   <li>{@code supervisedCallGuard != resolveCallGuard}: a supervised-targets outage must not trip the
 *       breaker every persona's {@code /internal/effective-role} resolution depends on.</li>
 * </ol>
 */
@SpringBootTest(properties = "catalog.role-source=http")
@Testcontainers
class SupervisedEdgeWiringIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    /** The stand-in user-service: it answers ONLY the supervised-targets endpoint. */
    static final HttpServer USER_SERVICE = startStub();

    static final AtomicReference<String> LAST_PATH = new AtomicReference<>();

    static {
        POSTGRES.start();
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    LAST_PATH.set(exchange.getRequestURI().getPath());
                    byte[] body = ("[\"" + SUPERVISED_ID + "\"]").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("could not start the user-service stub", e);
        }
    }

    static final UUID SUPERVISED_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Only the SHARED base-url is set — the supervised edge must inherit it by default.
        registry.add("catalog.user-service.base-url",
                () -> "http://127.0.0.1:" + USER_SERVICE.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        USER_SERVICE.stop(0);
    }

    @Autowired
    SupervisedScopeClient client;

    @Autowired
    @Qualifier("supervisedCallGuard")
    CallGuard supervisedGuard;

    @Autowired
    @Qualifier("resolveCallGuard")
    CallGuard resolveGuard;

    @Test
    void theSupervisedBaseUrlDefaultsToTheSharedOne() {
        List<UUID> ids = client.supervisedIds("sup-anna", "catalog");

        assertThat(ids).containsExactly(SUPERVISED_ID);
        assertThat(LAST_PATH.get()).isEqualTo("/internal/supervised-targets");
    }

    @Test
    void theSupervisedEdgeOwnsItsBreaker() {
        assertThat(supervisedGuard).isNotSameAs(resolveGuard);
        assertThat(((Resilience4jCallGuard) supervisedGuard).breaker())
                .as("a supervised-targets outage must not trip the resolve breaker")
                .isNotSameAs(((Resilience4jCallGuard) resolveGuard).breaker());
    }
}
