package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaDecision;
import dev.dmitriikonovalov.opaabac.core.PolicyEngineException;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.MethodAuthorizationDeniedHandler;
import org.springframework.security.core.Authentication;
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

    /** The annotation on the INTERFACE method — the impl is bare (the natural contract-first placement). */
    interface InterfaceSecuredService {
        @OpaPreAuthorize(action = "product:read", resourceType = "'product'")
        String read();
    }

    static class InterfaceSecuredServiceImpl implements InterfaceSecuredService {
        @Override
        public String read() {
            return "ok";
        }
    }

    private SecuredService proxyWith(boolean opaAllows) {
        ProxyFactory factory = new ProxyFactory(new SecuredServiceImpl());
        factory.addAdvisor(interceptorWith(opaAllows));
        return (SecuredService) factory.getProxy();
    }

    private AuthorizationManagerBeforeMethodInterceptor interceptorWith(boolean opaAllows) {
        OpaClient opaClient = mock(OpaClient.class);
        when(opaClient.decide(Mockito.any())).thenReturn(OpaDecision.of(opaAllows));
        RoleDefinitionSupplier supplier = mock(RoleDefinitionSupplier.class);
        when(supplier.lookup(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Optional.empty());

        var manager = new OpaPreAuthorizeAuthorizationManager(opaClient, supplier);
        // The factory takes an ObjectProvider (lazily drained on the first decision — see the
        // config's javadoc); the slice test supplies the manager through a trivial provider.
        org.springframework.beans.factory.ObjectProvider<OpaPreAuthorizeAuthorizationManager> provider =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public OpaPreAuthorizeAuthorizationManager getObject() {
                        return manager;
                    }
                };
        return new OpaMethodSecurityConfiguration().opaPreAuthorizeMethodInterceptor(provider);
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

    // --- ENGINE-ERRORS I1: an outage never reaches a masking denied-handler ------------------------

    /**
     * A manager that is ALSO a {@link MethodAuthorizationDeniedHandler} — exactly how Spring Security wires
     * {@code @HandleAuthorizationDenied}: the interceptor hands a denial to the manager itself when it
     * implements the handler interface (verified in the 7.0.7 bytecode). This one masks every denial into
     * a 200-style value, the shape that would silently hide an outage if one were routed to it.
     */
    private static final class MaskingManager
            implements AuthorizationManager<MethodInvocation>, MethodAuthorizationDeniedHandler {
        private final OpaPreAuthorizeAuthorizationManager delegate;
        private int masked;

        MaskingManager(OpaPreAuthorizeAuthorizationManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public AuthorizationResult authorize(
                java.util.function.Supplier<? extends Authentication> authentication, MethodInvocation invocation) {
            return delegate.authorize(authentication, invocation);
        }

        @Override
        public Object handleDeniedInvocation(MethodInvocation invocation, AuthorizationResult result) {
            masked++;
            return "masked";
        }
    }

    private SecuredService maskedProxy(MaskingManager manager) {
        ProxyFactory factory = new ProxyFactory(new SecuredServiceImpl());
        factory.addAdvisor(new AuthorizationManagerBeforeMethodInterceptor(
                AnnotationMatchingPointcut.forMethodAnnotation(OpaPreAuthorize.class), manager));
        return (SecuredService) factory.getProxy();
    }

    private static OpaPreAuthorizeAuthorizationManager managerWhoseOpa(OpaClient opaClient) {
        RoleDefinitionSupplier supplier = mock(RoleDefinitionSupplier.class);
        when(supplier.lookup(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
        return new OpaPreAuthorizeAuthorizationManager(opaClient, supplier);
    }

    @Test // the control: a plain deny IS routed to the masking handler (the mechanism under test is live)
    void aPlainDeny_goesThroughTheMaskingHandler() {
        authenticate();
        OpaClient opaClient = mock(OpaClient.class);
        when(opaClient.decide(Mockito.any())).thenReturn(OpaDecision.of(false));
        MaskingManager manager = new MaskingManager(managerWhoseOpa(opaClient));

        assertThat(maskedProxy(manager).read()).isEqualTo("masked");
        assertThat(manager.masked).isEqualTo(1);
    }

    @Test // I1 — an indeterminate decision propagates PAST the masking handler: never masked as a denial
    void anIndeterminateDecision_bypassesTheMaskingHandler() {
        authenticate();
        OpaClient opaClient = mock(OpaClient.class);
        when(opaClient.decide(Mockito.any()))
                .thenThrow(PolicyEngineException.transport("decide for path 'product'", null));
        MaskingManager manager = new MaskingManager(managerWhoseOpa(opaClient));
        SecuredService service = maskedProxy(manager);

        assertThatThrownBy(service::read)
                .isInstanceOf(AuthorizationIndeterminateException.class)
                .isInstanceOf(AccessDeniedException.class);
        assertThat(manager.masked).isZero();
    }

    @Test
    void unauthenticated_throwsAccessDenied() {
        SecuredService service = proxyWith(true); // OPA would allow, but no subject → fail-closed deny
        assertThatThrownBy(service::read).isInstanceOf(AccessDeniedException.class);
    }

    /**
     * The enforcement-dodge regression: {@code @OpaPreAuthorize} declared on the INTERFACE method must be
     * enforced under a class-based (CGLIB) proxy — Spring Boot's default proxying mode. Before the
     * pointcut searched the type hierarchy ({@code checkInherited}), this exact shape ran with no
     * enforcement and no error.
     */
    @Test
    void interfaceAnnotation_isEnforced_underClassProxy() {
        authenticate();
        ProxyFactory factory = new ProxyFactory(new InterfaceSecuredServiceImpl());
        factory.setProxyTargetClass(true); // CGLIB, as in a Boot app
        factory.addAdvisor(interceptorWith(false));
        InterfaceSecuredService service = (InterfaceSecuredService) factory.getProxy();

        assertThatThrownBy(service::read).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void interfaceAnnotation_allowsThroughManager_underClassProxy() {
        authenticate();
        ProxyFactory factory = new ProxyFactory(new InterfaceSecuredServiceImpl());
        factory.setProxyTargetClass(true);
        factory.addAdvisor(interceptorWith(true));
        InterfaceSecuredService service = (InterfaceSecuredService) factory.getProxy();

        assertThat(service.read()).isEqualTo("ok");
    }
}
