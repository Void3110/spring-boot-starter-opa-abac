package dev.dmitriikonovalov.example.mcp.authz;

import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves the principal's <strong>type-level</strong> authority — what they may do to <em>some</em>
 * resource of a type — for the tool-gate.
 *
 * <h2>Why a type-level ceiling exists at all</h2>
 * Since slice B4, authority in this repo is membership-scoped: a role definition is resolved for a
 * <em>specific</em> governed resource, and the shipped resolve API requires a resource id. The tool-gate
 * deliberately resolves no target — asking "may this agent invoke this tool at all?" before any row is
 * known — so it has no id to resolve against. This supplier bridges that gap using only shipped
 * endpoints: the targets the principal governs for the type, then the batch resolve over them, unioned.
 *
 * <h2>Union of grants, intersection of denials — and why over-approximating is safe</h2>
 * A principal who may write catalog A and only read catalog B has type-level authority "read and write
 * some catalog". Grants are therefore <strong>unioned</strong>, and a denial is kept only when it applies
 * to <em>every</em> governed target, since a denial on one resource says nothing about another.
 *
 * <p>The result deliberately over-approximates the principal's authority on any <em>particular</em>
 * resource, and that is sound because <strong>the tool-gate is not the authority on resources</strong>.
 * It can only ever let through a call the catalog service then decides properly, with the caller's own
 * bearer, using its own unchanged per-type policy. What the tool-gate does enforce exactly is the
 * <em>agent</em> narrowing — and the ceiling term is what keeps a capability from widening past the
 * human, which is the property this slice exists for. Under-approximating would be the dangerous
 * direction: it would deny legitimate use and tempt someone to disable the gate.
 *
 * <h2>Fail-closed classification (B2, applied verbatim in spirit)</h2>
 * An empty governed-target list is an <strong>authoritative no-role</strong> — the principal governs
 * nothing of this type — and returns {@link Optional#empty()}, which the policy default-denies. Every
 * other non-success — any non-200, a blank or malformed body, a timeout, a refused connection — throws
 * {@link RoleResolutionException}, because an unknown ceiling must never be read as "no ceiling" and
 * certainly never as "any ceiling".
 */
public class TypeLevelRoleDefinitionSupplier implements RoleDefinitionSupplier {

    private static final TypeReference<List<String>> ID_LIST = new TypeReference<>() {};
    private static final TypeReference<List<EffectiveRoleEntry>> ROLE_ENTRIES = new TypeReference<>() {};

    private static final Logger log = LoggerFactory.getLogger(TypeLevelRoleDefinitionSupplier.class);

    /**
     * Governed targets per {@code /internal/effective-roles} request. At roughly 50 bytes per
     * {@code &target=catalog%3A<uuid>} parameter this keeps the request line near 2.5 KB — comfortably
     * inside a servlet container's default budget, with room for the base URL and the subject.
     */
    private static final int RESOLVE_CHUNK = 50;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;
    private final List<String> grantScopeTypes;

    public TypeLevelRoleDefinitionSupplier(
            ObjectMapper objectMapper, String baseUrl, Duration timeout, List<String> grantScopeTypes) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.grantScopeTypes = List.copyOf(grantScopeTypes);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * The types to enumerate governed targets on, beyond the requested one.
     *
     * <h2>Why this is not just {@code resourceType} (found on the rig, 2026-07-31)</h2>
     * Since slice B4, membership — and therefore the role definition — is recorded on the
     * <strong>governing root</strong> ([[0018-team-scoped-resource-isolation|ADR 0018]]), while the role
     * itself carries permissions for the whole hierarchy: a {@code demo-editor} on a *catalog* grants
     * {@code catalog}, {@code category} <em>and</em> {@code product} actions. So
     * {@code /internal/governed-targets?resourceType=product} legitimately returns an <em>empty</em> list
     * for a principal who can nonetheless read every product under a catalog they govern.
     *
     * <p>Asking only for the requested type therefore <strong>under</strong>-approximated the ceiling —
     * the exact direction the class javadoc calls dangerous, and the direction that makes the tool
     * surface <em>remove</em> access the caller has over REST. On the demo rig it also erased the
     * slice's headline: with the human's own ceiling empty for {@code product}, an agent's
     * {@code get_product} denial proved nothing about agent narrowing, because the principal was being
     * denied for the same reason.
     */
    private Set<String> scopeTypesFor(String resourceType) {
        Set<String> types = new LinkedHashSet<>();
        types.add(resourceType);
        types.addAll(grantScopeTypes);
        return types;
    }

    @Override
    public Optional<RoleDefinition> lookup(String userId, String resourceType, String resourceId) {
        if (userId == null || resourceType == null) {
            return Optional.empty(); // no coordinates to resolve — authoritative no-role, not an outage
        }

        List<String> governed = new ArrayList<>();
        List<RoleDefinition> scopeRoles = new ArrayList<>();
        for (String scopeType : scopeTypesFor(resourceType)) {
            List<String> ids = governedTargets(userId, scopeType);
            if (ids.isEmpty()) {
                continue;
            }
            governed.addAll(ids);
            scopeRoles.addAll(resolveAll(userId, scopeType, ids));
        }
        if (governed.isEmpty()) {
            log.debug("No governed targets for '{}' across {} — authoritative no-role",
                    userId, scopeTypesFor(resourceType));
            return Optional.empty();
        }
        if (scopeRoles.isEmpty()) {
            // Governed targets exist but none resolved to a role — an authoritative no-role, not an
            // outage (an outage would already have thrown from exchange()).
            return Optional.empty();
        }
        return Optional.of(union(resourceType, scopeRoles));
    }

    private List<String> governedTargets(String userId, String resourceType) {
        URI uri = URI.create(baseUrl + "/internal/governed-targets"
                + "?subject=" + enc(userId)
                + "&resourceType=" + enc(resourceType));
        return parse(exchange(uri), ID_LIST, uri);
    }

    /**
     * Resolve every governed target's role, in <strong>chunks</strong>.
     *
     * <p>The batch is one {@code &target=} parameter per id, and this path is id-less by design — the
     * tool-gate asks about a <em>type</em>, so nothing upstream bounds the set the way a page bounds
     * the catalog service's equivalent call. A principal governing enough roots would push the request
     * line past the servlet container's limit, and the non-200 that follows is read as an outage: every
     * tool call would deny with {@code tool-gate-ceiling-unavailable}. Fail-closed, but for a reason no
     * operator could guess from the symptom. Chunking keeps each request a fixed size; the roles union
     * across chunks exactly as they union across scope types. (Deep review 2026-07-31.)
     */
    private List<RoleDefinition> resolveAll(String userId, String resourceType, List<String> ids) {
        List<RoleDefinition> roles = new ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += RESOLVE_CHUNK) {
            List<String> chunk = ids.subList(from, Math.min(from + RESOLVE_CHUNK, ids.size()));
            StringBuilder query = new StringBuilder("?userId=").append(enc(userId));
            for (String id : chunk) {
                query.append("&target=").append(enc(resourceType + ":" + id));
            }
            URI uri = URI.create(baseUrl + "/internal/effective-roles" + query);
            for (EffectiveRoleEntry entry : parse(exchange(uri), ROLE_ENTRIES, uri)) {
                if (entry.role() != null) {
                    roles.add(entry.role());
                }
            }
        }
        return roles;
    }

    /**
     * The type-level union: every granted category for the type, minus only those actions denied on
     * <strong>every</strong> governed target.
     */
    private static RoleDefinition union(String resourceType, List<RoleDefinition> roles) {
        Set<String> grants = new LinkedHashSet<>();
        for (RoleDefinition role : roles) {
            grants.addAll(permissionsFor(role, resourceType));
        }

        Set<String> denials = null;
        for (RoleDefinition role : roles) {
            Set<String> ofThisRole = new LinkedHashSet<>(deniedFor(role, resourceType));
            if (denials == null) {
                denials = ofThisRole;
            } else {
                denials.retainAll(ofThisRole);
            }
        }

        Map<String, List<String>> permissions = new LinkedHashMap<>();
        permissions.put(resourceType, List.copyOf(grants));
        Map<String, List<String>> deniedActions = new LinkedHashMap<>();
        if (denials != null && !denials.isEmpty()) {
            deniedActions.put(resourceType, List.copyOf(denials));
        }

        // No required_tags: a tag requirement is a per-resource narrowing, and the tool-gate does not
        // decide resources. The catalog service applies it, unchanged, on the row the tool touches.
        return new RoleDefinition(
                "type-level:" + resourceType, Map.of(), permissions, deniedActions, Map.of(), null);
    }

    private static List<String> permissionsFor(RoleDefinition role, String resourceType) {
        List<String> tokens = role.permissions().get(resourceType);
        if (tokens == null) {
            tokens = role.permissions().get("*");
        }
        return tokens == null ? List.of() : tokens;
    }

    private static List<String> deniedFor(RoleDefinition role, String resourceType) {
        Map<String, List<String>> denied = role.deniedActions();
        if (denied == null) {
            return List.of();
        }
        List<String> actions = denied.get(resourceType);
        if (actions == null) {
            actions = denied.get("*");
        }
        return actions == null ? List.of() : actions;
    }

    /** One GET. Only a 200 with a body is trusted; everything else is an outage. */
    private String exchange(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Type-level role resolve failed ({}) — failing closed", e.getClass().getSimpleName());
            throw new RoleResolutionException("role source unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RoleResolutionException("role resolve interrupted", e);
        }

        if (response.statusCode() != 200) {
            log.warn("Type-level role resolve returned HTTP {} — failing closed", response.statusCode());
            throw new RoleResolutionException("role source returned HTTP " + response.statusCode());
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new RoleResolutionException("role source returned an empty body");
        }
        return body;
    }

    private <T> T parse(String body, TypeReference<T> type, URI uri) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JacksonException e) {
            log.warn("Type-level role resolve returned an unreadable body for {}", uri.getPath());
            throw new RoleResolutionException("role source returned a malformed body", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The shipped batch-resolve entry shape. */
    private record EffectiveRoleEntry(String resourceType, String resourceId, RoleDefinition role) {}
}
