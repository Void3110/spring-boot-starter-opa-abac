package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Method-security slice: the real {@link AuthorizationManagerBeforeMethodInterceptor} from
 * {@link OpaMethodSecurityConfiguration} (its pointcut bound to {@link OpaPreAuthorize}) is applied to a
 * proxied bean, so an annotated method runs on allow and throws {@link AccessDeniedException} on deny
 * (QA U22/U23 at the interceptor layer).
 */
class OpaMethodSecuritySliceTest {

    interface SecuredService {
        String read();
    }

    static class SecuredServiceImpl implements SecuredService {
        @Override
        @OpaPreAuthorize(action = "product:read", resourceType = "'product'")
        public String read() {
            return "ok";
        }
    }

    private SecuredService proxyWith(boolean opaAllows) {
        OpaClient opaClient = mock(OpaClient.class);
        when(opaClient.allow(Mockito.any())).thenReturn(opaAllows);
        RoleDefinitionSupplier supplier = mock(RoleDefinitionSupplier.class);
        when(supplier.lookup(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Optional.empty());

        var manager = new OpaPreAuthorizeAuthorizationManager(opaClient, supplier);
        AuthorizationManagerBeforeMethodInterceptor interceptor =
                new OpaMethodSecurityConfiguration().opaPreAuthorizeMethodInterceptor(manager);

        // Proxy the concrete target; the interceptor's own pointcut (matching @OpaPreAuthorize) gates it.
        ProxyFactory factory = new ProxyFactory(new SecuredServiceImpl());
        factory.addAdvisor(interceptor);
        return (SecuredService) factory.getProxy();
    }

    private void authenticate() {
        AbacContext.Subject subject =
                new AbacContext.Subject("user-1", List.of("catalog-viewer"), Map.of());
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(subject));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allow_methodRuns() {
        authenticate();
        SecuredService service = proxyWith(true);
        assertThat(service.read()).isEqualTo("ok");
    }

    @Test
    void deny_throwsAccessDenied() {
        authenticate();
        SecuredService service = proxyWith(false);
        assertThatThrownBy(service::read).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unauthenticated_throwsAccessDenied() {
        SecuredService service = proxyWith(true); // OPA would allow, but no subject → fail-closed deny
        assertThatThrownBy(service::read).isInstanceOf(AccessDeniedException.class);
    }
}
