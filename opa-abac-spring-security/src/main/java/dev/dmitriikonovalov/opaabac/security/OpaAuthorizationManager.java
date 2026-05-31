package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Opt-in request-level OPA authorization: an {@link AuthorizationManager} over a
 * {@link RequestAuthorizationContext}, for apps that want a coarse HTTP-level rule in their security
 * chain (e.g. {@code .anyRequest().access(opaAuthorizationManager)}).
 *
 * <p>The action is the lowercased HTTP method; the resource type is looked up from a configured
 * path-prefix → type map (longest-prefix wins), or a fallback type. The headline mechanism remains
 * {@link OpaPreAuthorize}, which can name the concrete resource type and action; this is provided for
 * completeness and wired by the app only if it wants it.
 *
 * <p>Fail-closed: unauthenticated or any exception denies.
 */
public final class OpaAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final Logger log = LoggerFactory.getLogger(OpaAuthorizationManager.class);
    private static final AuthorizationDecision DENY = new AuthorizationDecision(false);

    private final OpaClient opaClient;
    private final RoleDefinitionSupplier roleDefinitionSupplier;
    private final Map<String, String> pathPrefixToType;
    private final String fallbackResourceType;

    public OpaAuthorizationManager(
            OpaClient opaClient,
            RoleDefinitionSupplier roleDefinitionSupplier,
            Map<String, String> pathPrefixToType,
            String fallbackResourceType) {
        this.opaClient = Objects.requireNonNull(opaClient, "opaClient");
        this.roleDefinitionSupplier =
                Objects.requireNonNull(roleDefinitionSupplier, "roleDefinitionSupplier");
        this.pathPrefixToType = pathPrefixToType == null ? Map.of() : Map.copyOf(pathPrefixToType);
        this.fallbackResourceType = fallbackResourceType;
    }

    @Override
    public AuthorizationDecision check(
            Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        try {
            Authentication auth = authentication.get();
            if (!(auth instanceof AbacAuthentication abac) || !abac.isAuthenticated()) {
                return DENY;
            }
            HttpServletRequest request = context.getRequest();
            String action = request.getMethod().toLowerCase(Locale.ROOT);
            String type = resolveType(request.getRequestURI());
            if (type == null) {
                return DENY;
            }
            AbacContext.Subject subject = abac.getSubject();
            RoleDefinition roleDefinition =
                    roleDefinitionSupplier.lookup(subject.id(), type, null).orElse(null);
            AbacContext abacContext = new AbacContext(
                    subject, action, new AbacContext.Resource(type, null, Map.of()), roleDefinition, Map.of());
            return new AuthorizationDecision(opaClient.allow(abacContext));
        } catch (Exception e) {
            log.warn("OPA request authorization denied (fail-closed): {}", e.getClass().getSimpleName());
            return DENY;
        }
    }

    private String resolveType(String path) {
        String bestPrefix = null;
        for (String prefix : pathPrefixToType.keySet()) {
            if (path.startsWith(prefix) && (bestPrefix == null || prefix.length() > bestPrefix.length())) {
                bestPrefix = prefix;
            }
        }
        return bestPrefix != null ? pathPrefixToType.get(bestPrefix) : fallbackResourceType;
    }
}
