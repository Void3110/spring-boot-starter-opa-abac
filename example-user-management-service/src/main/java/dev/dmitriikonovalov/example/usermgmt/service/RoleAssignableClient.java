package dev.dmitriikonovalov.example.usermgmt.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dmitriikonovalov.example.usermgmt.domain.RoleDefinitionEntity;
import dev.dmitriikonovalov.opaabac.autoconfigure.OpaAbacProperties;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The app-side client for the {@code data.role.assignable} verdict (Phase 6.5) — the senior tier's
 * subset-on-effective check, computed in OPA over two <b>raw row snapshots</b> (stored
 * {@code permissions}/{@code denied_actions}; the policy's shared {@code effective_actions} does the
 * wildcard-aware expansion).
 *
 * <p>App-side (not the library) by design: {@code OpaClient} exposes no arbitrary-entrypoint call and
 * the library stays untouched this slice. Reuses the starter's configured OPA base-url.
 *
 * <p><b>FAIL-CLOSED</b>: any error — connect/read timeout, non-2xx, an unparseable body, a missing
 * {@code result} — answers {@code false} (not assignable). An OPA outage is deliberately
 * indistinguishable from "not assignable" (one rejection contract; pinned semantic #1).
 */
@Component
public class RoleAssignableClient {

    private static final Logger log = LoggerFactory.getLogger(RoleAssignableClient.class);

    private final RestClient restClient;

    public RoleAssignableClient(OpaAbacProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(Duration.ofSeconds(2))
                                .withReadTimeout(Duration.ofSeconds(2))))
                .build();
    }

    /**
     * True iff OPA positively answers that {@code candidate}'s effective actions are a subset of
     * {@code actor}'s on every type the candidate grants. Anything else — including any failure to
     * get an answer — is {@code false}.
     */
    public boolean assignable(RoleDefinitionEntity actor, RoleDefinitionEntity candidate) {
        try {
            JsonNode response = restClient.post()
                    .uri("/v1/data/role/assignable")
                    .body(Map.of("input", Map.of(
                            "actor_role", snapshot(actor),
                            "candidate_role", snapshot(candidate))))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("result").isBoolean()) {
                log.warn("assignable verdict missing/non-boolean result — rejecting (fail-closed)");
                return false;
            }
            return response.get("result").asBoolean();
        } catch (RuntimeException e) {
            log.warn("assignable verdict unavailable ({}) — rejecting (fail-closed)", e.getMessage());
            return false;
        }
    }

    /** The raw row snapshot the policy expects — stored grants + denials, no wildcard expansion. */
    private static Map<String, Object> snapshot(RoleDefinitionEntity role) {
        return Map.of(
                "permissions", role.getPermissions(),
                "denied_actions", role.getDeniedActions());
    }
}
