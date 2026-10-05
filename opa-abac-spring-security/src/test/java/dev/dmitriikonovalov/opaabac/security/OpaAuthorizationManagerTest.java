package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PolicyEngineException;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
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
    // ENGINE-ERRORS U19: thrown as "could not decide" (ADR 0037 §5) — still an AccessDeniedException, so the
    // filter chain's AccessDeniedHandler answers it (403 by default); OPA is never asked (ADR 0014).
    void roleSourceOutage_isIndeterminate_neverCallsOpa() {
        RoleResolutionException outage = new RoleResolutionException("source unavailable");
        when(supplier.lookup(any(), any(), any())).thenThrow(outage);

        assertThatThrownBy(() -> manager().authorize(authenticated(), requestContext("POST", "/api/v1/products")))
                .isInstanceOf(AuthorizationIndeterminateException.class)
                .isInstanceOf(AccessDeniedException.class)
                .hasCause(outage);
        verify(opaClient, never()).allow(any());
    }

    @Test // ENGINE-ERRORS U19 — the policy engine could not decide → thrown, with the engine failure as cause
    void engineFailure_isIndeterminate() {
        when(supplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        PolicyEngineException failure = PolicyEngineException.httpStatus(503, "decide for path 'product'");
        when(opaClient.allow(any())).thenThrow(failure);

        assertThatThrownBy(() -> manager().authorize(authenticated(), requestContext("GET", "/api/v1/products")))
                .isInstanceOf(AuthorizationIndeterminateException.class)
                .hasCause(failure);
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
