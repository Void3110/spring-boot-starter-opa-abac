package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** Unit tests for the opt-in request-level {@link OpaAuthorizationManager}. */
class OpaAuthorizationManagerTest {

    private final OpaClient opaClient = mock(OpaClient.class);
    private final RoleDefinitionSupplier supplier = mock(RoleDefinitionSupplier.class);

    private OpaAuthorizationManager manager() {
        return new OpaAuthorizationManager(
                opaClient, supplier, Map.of("/api/v1/products", "product", "/api/v1", "catalog"), "catalog");
    }

    private RequestAuthorizationContext requestContext(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        return new RequestAuthorizationContext(request);
    }

    private Supplier<Authentication> authenticated() {
        AbacContext.Subject subject = new AbacContext.Subject("user-1", List.of("catalog-viewer"), Map.of());
        return () -> new AbacAuthentication(subject);
    }

    @Test
    void allow_lowercasedMethodAndLongestPrefixType() {
        when(supplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenReturn(true);

        AuthorizationDecision decision =
                manager().authorize(authenticated(), requestContext("GET", "/api/v1/products/42"));

        assertThat(decision.isGranted()).isTrue();
        ArgumentCaptor<AbacContext> captor = ArgumentCaptor.forClass(AbacContext.class);
        verify(opaClient).allow(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("get");
        assertThat(captor.getValue().resource().type()).isEqualTo("product"); // longest-prefix wins
    }

    @Test
    void unauthenticated_deny() {
        AuthorizationDecision decision =
                manager().authorize(() -> null, requestContext("GET", "/api/v1/products"));
        assertThat(decision.isGranted()).isFalse();
    }

    @Test
    void opaError_failClosedDeny() {
        when(supplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenThrow(new RuntimeException("boom"));

        AuthorizationDecision decision =
                manager().authorize(authenticated(), requestContext("POST", "/api/v1/products"));
        assertThat(decision.isGranted()).isFalse();
    }

    @Test // B2 U4 — supplier throws RoleResolutionException (outage) → deny, OpaClient NEVER invoked
    // (no empty-role context reaches OPA's realm fallback). Mirror of the @OpaPreAuthorize manager.
    void roleSourceOutage_failClosedDeny_neverCallsOpa() {
        when(supplier.lookup(any(), any(), any()))
                .thenThrow(new dev.dmitriikonovalov.opaabac.core.RoleResolutionException("source unavailable"));

        AuthorizationDecision decision =
                manager().authorize(authenticated(), requestContext("POST", "/api/v1/products"));

        assertThat(decision.isGranted()).isFalse();
        verify(opaClient, never()).allow(any());
    }

    @Test // B2 U4 sibling — authoritative no-role (Optional.empty()) → OPA still called (fallback decides).
    void authoritativeNoRole_callsOpa() {
        when(supplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenReturn(true);

        AuthorizationDecision decision =
                manager().authorize(authenticated(), requestContext("GET", "/api/v1/products"));

        assertThat(decision.isGranted()).isTrue();
        verify(opaClient).allow(any());
    }
}
