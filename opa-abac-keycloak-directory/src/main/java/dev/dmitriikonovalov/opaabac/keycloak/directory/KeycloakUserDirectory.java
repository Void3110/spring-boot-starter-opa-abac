package dev.dmitriikonovalov.opaabac.keycloak.directory;

import dev.dmitriikonovalov.opaabac.security.directory.DirectoryUser;
import dev.dmitriikonovalov.opaabac.security.directory.UserDirectory;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.client.ClientBuilder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link UserDirectory} over the Keycloak admin REST API (ADR 0020 §3/§4/§8): a
 * {@code client_credentials} service account (a dedicated client holding only
 * {@code realm-management → view-users}) searching {@code GET /admin/realms/{realm}/users?search=…}.
 *
 * <h2>Fail-closed, no-oracle</h2>
 * Every non-affirmative outcome is an <strong>empty list</strong>, never an exception past this method:
 * Keycloak unreachable / timeout / 5xx, a failed or unauthorized token grant, a blank {@code query}
 * (which never even contacts Keycloak — the realm is not enumerable), zero matches. Outage vs
 * genuine-empty differ <em>only</em> in the WARN log — the caller and the UI cannot tell them apart
 * (the deliberate no-oracle property, §8). The {@code limit} is clamped here, in the implementation
 * ({@code <= 0} → {@value #DEFAULT_LIMIT}, {@code > }{@value #MAX_LIMIT} → {@value #MAX_LIMIT}), and
 * re-enforced on the response — a misbehaving server cannot widen the bound.
 *
 * <h2>Disclosure</h2>
 * Maps only {@code id → subject} (the IdP {@code sub} — the join key the application provisions
 * against) and {@code username → displayName} (falling back to {@code subject} when blank, so a row is
 * always renderable). Everything else the admin API returns (email, names, attributes) stops here —
 * {@link DirectoryUser} is the ceiling (§7).
 *
 * <p>HTTP timeouts are fixed at {@value #TIMEOUT_MS}&nbsp;ms (connect and read): the port backs an
 * interactive picker, so a slow directory must degrade to the no-oracle empty quickly rather than hang
 * the request thread. Not configurable by design — the property surface stays the pinned five.
 */
public final class KeycloakUserDirectory implements UserDirectory, AutoCloseable {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    static final int TIMEOUT_MS = 3000;

    private static final Logger log = LoggerFactory.getLogger(KeycloakUserDirectory.class);

    private final Keycloak keycloak;
    private final String realm;

    public KeycloakUserDirectory(KeycloakDirectoryProperties properties) {
        this.realm = properties.getRealm();
        // Constructing the client performs no I/O; the token is granted lazily on first use — a
        // misconfigured or down Keycloak surfaces as the fail-closed empty in search(), never at startup.
        this.keycloak = KeycloakBuilder.builder()
                .serverUrl(properties.getServerUrl())
                .realm(properties.getRealm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .resteasyClient(ClientBuilder.newBuilder()
                        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build())
                .build();
    }

    @Override
    public List<DirectoryUser> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            // Never enumerate the realm: a blank query is answered locally, no Keycloak call (§8).
            return List.of();
        }
        int clamped = clamp(limit);
        try {
            return keycloak.realm(realm).users().search(query.strip(), 0, clamped).stream()
                    .limit(clamped) // re-enforce: the bound holds even if the server ignores `max`
                    .map(KeycloakUserDirectory::toDirectoryUser)
                    .toList();
        } catch (Exception e) {
            // The admin client wraps the grant's 401 before it reaches us — classify by cause chain.
            if (isAuthFailure(e)) {
                log.warn("user-directory token grant/authorization failed ({}) — empty result (fail-closed, no-oracle)",
                        e.getMessage());
            } else {
                log.warn("user-directory search failed ({}) — empty result (fail-closed, no-oracle)", e.getMessage());
            }
            return List.of();
        }
    }

    private static boolean isAuthFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof NotAuthorizedException || t instanceof ForbiddenException) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static DirectoryUser toDirectoryUser(UserRepresentation user) {
        String subject = user.getId();
        String username = user.getUsername();
        return new DirectoryUser(subject, username == null || username.isBlank() ? subject : username);
    }

    @Override
    public void close() {
        keycloak.close();
    }
}
