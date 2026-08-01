package dev.dmitriikonovalov.example.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The principal's type-level ceiling, over its real HTTP edge.
 *
 * <p>Added by the deep review (2026-07-31): this class is one of the tool-gate's two inputs — the other
 * being the agent capability — and it shipped with no tests at all, which is how the grant-scope defect
 * below reached the rig. The cases here are written against the two user-service endpoints it actually
 * calls, with a stub standing in for the service, so a regression shows up as a wrong ceiling rather
 * than as a mystifying policy denial three layers away.
 */
class TypeLevelRoleDefinitionSupplierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER = "user-alice";

    private HttpServer stub;
    private final List<String> requested = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    /**
     * THE regression case. Membership lives on the governing root (ADR 0018) while the role it resolves
     * to carries the whole hierarchy's permissions — so asking only for the requested type returns an
     * empty governed set for a principal who may read every product under a catalog they govern.
     *
     * <p>On the rig this made the tool surface REMOVE access the caller had over REST, and denied the
     * human and the agent for the same reason, erasing the slice's headline contrast.
     */
    @Test
    void resolvesTheCeilingFromTheGoverningRootWhenTheRequestedTypeGovernsNothing() throws IOException {
        String base = startStub(Map.of(
                "product", List.of(),
                "catalog", List.of("cat-1")));

        Optional<RoleDefinition> ceiling = supplier(base, List.of("catalog"))
                .lookup(USER, "product", null);

        assertThat(ceiling).isPresent();
        assertThat(ceiling.get().permissions().get("product")).containsExactly("READ", "WRITE");
        assertThat(requested)
                .as("both scope types are enumerated — the requested one AND the governing root")
                .anyMatch(u -> u.contains("resourceType=product"))
                .anyMatch(u -> u.contains("resourceType=catalog"));
    }

    /** With the grant scope removed, the same rig state under-approximates — the bug, pinned. */
    @Test
    void withoutTheGrantScopeTheSameStateResolvesNoCeilingAtAll() throws IOException {
        String base = startStub(Map.of(
                "product", List.of(),
                "catalog", List.of("cat-1")));

        assertThat(supplier(base, List.of()).lookup(USER, "product", null))
                .as("this is what the tool surface saw before the grant-scope fix")
                .isEmpty();
    }

    /** A principal who governs nothing anywhere is an authoritative no-role — empty, never an outage. */
    @Test
    void aPrincipalGoverningNothingResolvesToAnAuthoritativeEmpty() throws IOException {
        String base = startStub(Map.of("product", List.of(), "catalog", List.of()));

        assertThat(supplier(base, List.of("catalog")).lookup(USER, "product", null)).isEmpty();
    }

    /**
     * The distinction the whole tri-state doctrine rests on: an OUTAGE must not look like "no role".
     * An empty ceiling denies quietly; an outage must deny with its own code so it is diagnosable.
     */
    @Test
    void anOutageThrowsRatherThanDegradingToAnEmptyCeiling() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            try {
                respond(exchange, 503, "{\"status\":503}");
            } finally {
                exchange.close();
            }
        });
        stub.start();
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();

        assertThatThrownBy(() -> supplier(base, List.of("catalog")).lookup(USER, "product", null))
                .isInstanceOf(RoleResolutionException.class);
    }

    /** Missing coordinates decide nothing and reach nobody — no request, no exception, just empty. */
    @Test
    void missingCoordinatesResolveToEmptyWithoutCallingTheService() throws IOException {
        String base = startStub(Map.of("product", List.of("p-1")));

        assertThat(supplier(base, List.of("catalog")).lookup(null, "product", null)).isEmpty();
        assertThat(supplier(base, List.of("catalog")).lookup(USER, null, null)).isEmpty();
        assertThat(requested).isEmpty();
    }

    /** Grants union across the governed targets; a denial survives only if EVERY target denies it. */
    @Test
    void unionsGrantsAcrossTargetsAndKeepsOnlyTheDenialsCommonToAllOfThem() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/internal/governed-targets", exchange -> {
            try {
                respond(exchange, 200, "[\"cat-1\",\"cat-2\"]");
            } finally {
                exchange.close();
            }
        });
        stub.createContext("/internal/effective-roles", exchange -> {
            try {
                // cat-1 grants READ and denies "delete"; cat-2 grants WRITE and denies "delete"+"tag".
                respond(exchange, 200, """
                        [{"resourceType":"catalog","resourceId":"cat-1","role":{"code":"a",
                          "permissions":{"catalog":["READ"]},"denied_actions":{"catalog":["delete"]}}},
                         {"resourceType":"catalog","resourceId":"cat-2","role":{"code":"b",
                          "permissions":{"catalog":["WRITE"]},"denied_actions":{"catalog":["delete","tag"]}}}]
                        """);
            } finally {
                exchange.close();
            }
        });
        stub.start();
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();

        RoleDefinition ceiling = supplier(base, List.of()).lookup(USER, "catalog", null).orElseThrow();

        assertThat(ceiling.permissions().get("catalog")).containsExactlyInAnyOrder("READ", "WRITE");
        assertThat(ceiling.deniedActions().get("catalog"))
                .as("'tag' is denied on only ONE target, so it is not a TYPE-level denial")
                .containsExactly("delete");
    }

    /**
     * A principal governing many roots must not build one enormous request line. This path is id-less
     * by design, so nothing upstream bounds the target set the way a page bounds the catalog service's
     * equivalent call — and the non-200 an over-long URI earns is read as an OUTAGE, denying every tool
     * call with a code no operator could trace back to "too many teams".
     */
    @Test
    void chunksTheResolveCallSoALargeGovernedSetCannotOverflowTheRequestLine() throws IOException {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            many.add(String.format("cat-%03d", i));
        }
        List<Integer> targetsPerRequest = new CopyOnWriteArrayList<>();

        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/internal/governed-targets", exchange -> {
            try {
                List<String> quoted = new ArrayList<>();
                for (String id : many) {
                    quoted.add("\"" + id + "\"");
                }
                respond(exchange, 200, "[" + String.join(",", quoted) + "]");
            } finally {
                exchange.close();
            }
        });
        stub.createContext("/internal/effective-roles", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                targetsPerRequest.add(query.split("&target=", -1).length - 1);
                respond(exchange, 200, """
                        [{"resourceType":"catalog","resourceId":"cat-000","role":{"code":"steward",
                          "permissions":{"catalog":["READ"]}}}]
                        """);
            } finally {
                exchange.close();
            }
        });
        stub.start();
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();

        RoleDefinition ceiling = supplier(base, List.of()).lookup(USER, "catalog", null).orElseThrow();

        assertThat(ceiling.permissions().get("catalog")).containsExactly("READ");
        assertThat(targetsPerRequest)
                .as("120 governed targets are split across requests, none of them unbounded")
                .hasSizeGreaterThan(1)
                .allMatch(count -> count <= 50);
        assertThat(targetsPerRequest.stream().mapToInt(Integer::intValue).sum())
                .as("every target is still resolved — chunking must not drop any")
                .isEqualTo(120);
    }

    /**
     * The ceiling carries no {@code required_tags}. A tag requirement narrows a specific resource, and
     * the tool-gate decides no resource — the catalog service applies it, unchanged, on the row the tool
     * actually touches. Carrying it here would deny type-level calls a tag might well have permitted.
     */
    @Test
    void dropsRequiredTagsBecauseTheToolGateDecidesNoResource() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/internal/governed-targets", exchange -> {
            try {
                respond(exchange, 200, "[\"cat-1\"]");
            } finally {
                exchange.close();
            }
        });
        stub.createContext("/internal/effective-roles", exchange -> {
            try {
                respond(exchange, 200, """
                        [{"resourceType":"catalog","resourceId":"cat-1","role":{"code":"gated",
                          "permissions":{"catalog":["READ"]},
                          "required_tags":{"region":["emea"]},"match_mode":"ANY_OF"}}]
                        """);
            } finally {
                exchange.close();
            }
        });
        stub.start();
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();

        RoleDefinition ceiling = supplier(base, List.of()).lookup(USER, "catalog", null).orElseThrow();

        assertThat(ceiling.requiredTags()).isEmpty();
        assertThat(ceiling.permissions().get("catalog")).containsExactly("READ");
    }

    // --- helpers ------------------------------------------------------------------------------

    private TypeLevelRoleDefinitionSupplier supplier(String baseUrl, List<String> grantScopeTypes) {
        return new TypeLevelRoleDefinitionSupplier(
                MAPPER, baseUrl, Duration.ofSeconds(2), grantScopeTypes);
    }

    /** A stub whose governed-target answer depends on the requested {@code resourceType}. */
    private String startStub(Map<String, List<String>> governedByType) throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/internal/governed-targets", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                requested.add(query == null ? "" : query);
                String type = typeFrom(query);
                List<String> ids = governedByType.getOrDefault(type, List.of());
                List<String> quoted = new ArrayList<>();
                for (String id : ids) {
                    quoted.add("\"" + id + "\"");
                }
                respond(exchange, 200, "[" + String.join(",", quoted) + "]");
            } finally {
                exchange.close();
            }
        });
        stub.createContext("/internal/effective-roles", exchange -> {
            try {
                respond(exchange, 200, """
                        [{"resourceType":"catalog","resourceId":"cat-1","role":{"code":"steward",
                          "permissions":{"catalog":["READ"],"product":["READ","WRITE"]}}}]
                        """);
            } finally {
                exchange.close();
            }
        });
        stub.start();
        return "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    private static String typeFrom(String query) {
        if (query == null) {
            return "";
        }
        for (String part : query.split("&")) {
            if (part.startsWith("resourceType=")) {
                return part.substring("resourceType=".length());
            }
        }
        return "";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
