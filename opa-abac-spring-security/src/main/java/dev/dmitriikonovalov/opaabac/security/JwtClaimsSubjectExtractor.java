package dev.dmitriikonovalov.opaabac.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link AbacSubjectExtractor}: reads the forwarded {@code Authorization: Bearer <jwt>},
 * base64url-decodes the <strong>payload segment only</strong>, and maps claims to a
 * {@link AbacContext.Subject}.
 *
 * <h2>Signature-trust posture (deliberate)</h2>
 * This extractor performs <strong>no signature verification</strong>. The application trusts a
 * validating gateway (e.g. APISIX {@code openid-connect}) to have verified the token against the realm
 * JWKS before forwarding it. Only structural checks (three segments, a JSON-object payload) and an
 * {@code exp} check (toggleable) are done — cheap defense-in-depth with no key material. This is safe
 * <em>only</em> behind such a gateway; deployed gateway-less it is a vulnerability, which is why a
 * verifying mode is reserved for a later phase.
 *
 * <p>Returns {@link Optional#empty()} on any problem (no/blank header, wrong segment count, non-JSON
 * payload, missing id, expired — including a missing or non-numeric {@code exp} while expiry
 * validation is on) — it never throws.
 */
public final class JwtClaimsSubjectExtractor implements AbacSubjectExtractor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ObjectMapper objectMapper;
    private final SubjectClaimsConfig claims;

    public JwtClaimsSubjectExtractor(ObjectMapper objectMapper, SubjectClaimsConfig claims) {
        this.objectMapper = objectMapper;
        this.claims = claims == null ? SubjectClaimsConfig.defaults() : claims;
    }

    @Override
    public Optional<AbacContext.Subject> extract(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return parsePayload(token).flatMap(this::toSubject);
    }

    /** Structural check + base64url-decode of the payload segment only (no signature verification). */
    private Optional<JsonNode> parsePayload(String token) {
        String[] segments = token.split("\\.");
        if (segments.length != 3) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(segments[1]);
            JsonNode payload = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            if (payload == null || !payload.isObject()) {
                return Optional.empty();
            }
            return Optional.of(payload);
        } catch (Exception e) {
            // malformed base64 / non-JSON payload → not a usable token
            return Optional.empty();
        }
    }

    private Optional<AbacContext.Subject> toSubject(JsonNode payload) {
        if (claims.validateExpiry() && isExpired(payload)) {
            return Optional.empty();
        }
        String id = textAt(payload, claims.idClaim());
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        List<String> roles = stringArrayAt(payload, claims.rolesClaim());
        Map<String, Object> attributes = new LinkedHashMap<>();
        String username = textAt(payload, claims.usernameClaim());
        if (username != null) {
            attributes.put("username", username);
        }
        for (String claim : claims.attributeClaims()) {
            JsonNode node = payload.get(claim);
            if (node != null && !node.isNull()) {
                attributes.put(claim, objectMapper.convertValue(node, Object.class));
            }
        }
        return Optional.of(new AbacContext.Subject(id, roles, attributes));
    }

    private boolean isExpired(JsonNode payload) {
        JsonNode exp = payload.get("exp");
        if (exp == null || !exp.isNumber()) {
            // Fail closed: with expiry validation enabled, a token that carries no verifiable exp is
            // rejected — accepting it would make the check trivially bypassable by dropping the claim.
            return true;
        }
        return Instant.ofEpochSecond(exp.asLong()).isBefore(Instant.now());
    }

    /** Read a (possibly dotted) claim path as text. */
    private static String textAt(JsonNode root, String path) {
        JsonNode node = at(root, path);
        return (node != null && node.isValueNode()) ? node.asString() : null;
    }

    /** Read a (possibly dotted) claim path as a list of strings; missing → empty list. */
    private static List<String> stringArrayAt(JsonNode root, String path) {
        JsonNode node = at(root, path);
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> {
                if (element.isValueNode()) {
                    values.add(element.asString());
                }
            });
        }
        return values;
    }

    /** Navigate a dotted path (e.g. {@code realm_access.roles}) into the claims tree. */
    private static JsonNode at(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }
}
