package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.OpaDecision;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit tests for {@link OpaPreAuthorizeAuthorizationManager} — QA cases U22–U29. */
class OpaPreAuthorizeAuthorizationManagerTest {

    private final OpaClient opaClient = mock(OpaClient.class);
    private final RoleDefinitionSupplier roleDefinitionSupplier = mock(RoleDefinitionSupplier.class);
    private final OpaPreAuthorizeAuthorizationManager manager =
            new OpaPreAuthorizeAuthorizationManager(opaClient, roleDefinitionSupplier);

    private final Supplier<Authentication> noopAuthSupplier = () -> null; // manager reads from the context

    @BeforeEach
    void authenticate() {
        AbacContext.Subject subject =
                new AbacContext.Subject("user-1", List.of("catalog-editor"), Map.of("username", "alice"));
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(subject));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // --- a sample annotated target -------------------------------------------

    @SuppressWarnings("unused")
    static class SampleController {
        @OpaPreAuthorize(action = "product:read", resourceType = "'product'")
        public void read() {}

        @OpaPreAuthorize(action = "product:write", resourceType = "'product'", resourceId = "#id")
        public void writeById(UUID id) {}

        @OpaPreAuthorize(action = "product:write", resource = "#product")
        public void writeInstance(SampleProduct product) {}

        @OpaPreAuthorize(action = "product:read", resourceType = "#missing")
        public void unresolvableType() {}

        // Slice B4: a type-level child create whose ROLE is resolved on the parent catalog (the
        // governing root), while the decided resource/policy stays `category`.
        @OpaPreAuthorize(action = "category:create", resourceType = "'category'",
                roleResourceType = "'catalog'", roleResourceId = "#catalogId")
        public void createChild(UUID catalogId) {}

        // The override declared but its id resolves to null (a typo'd #param) → deny (fail-closed).
        @OpaPreAuthorize(action = "category:create", resourceType = "'category'",
                roleResourceType = "'catalog'", roleResourceId = "#missing")
        public void createChildBadOverride(UUID catalogId) {}

        public void unannotated() {}
    }

    record SampleProduct(String id) implements AbacResource {
        @Override public String abacResourceType() { return "product"; }
        @Override public String abacResourceId() { return id; }
        @Override public Map<String, Object> abacAttributes() { return Map.of("status", "draft"); }
    }

    private MethodInvocation invocationOf(String methodName, Class<?>[] paramTypes, Object[] args) throws Exception {
        SampleController target = new SampleController();
        Method method = SampleController.class.getMethod(methodName, paramTypes);
        MethodInvocation invocation = mock(MethodInvocation.class);
        lenient().when(invocation.getMethod()).thenReturn(method);
        lenient().when(invocation.getThis()).thenReturn(target);
        lenient().when(invocation.getArguments()).thenReturn(args);
        return invocation;
    }

    // --- cases ---------------------------------------------------------------

    @Test // U22 — OPA allows → granted
    void allow_granted() throws Exception {
        when(opaClient.decide(any())).thenReturn(OpaDecision.of(true));
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());

        AuthorizationDecision decision = manager.authorize(noopAuthSupplier,
                invocationOf("read", new Class<?>[] {}, new Object[] {}));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
    }

    @Test // U23 — OPA denies → not granted
    void deny_notGranted() throws Exception {
        when(opaClient.decide(any())).thenReturn(OpaDecision.of(false));
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());

        AuthorizationDecision decision = manager.authorize(noopAuthSupplier,
                invocationOf("read", new Class<?>[] {}, new Object[] {}));

        assertThat(decision.isGranted()).isFalse();
    }

    @Test // U24 — OPA client throws → fail-closed deny
    void opaError_failClosedDeny() throws Exception {
        when(opaClient.decide(any())).thenThrow(new RuntimeException("transport blew up"));
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());

        AuthorizationDecision decision = manager.authorize(noopAuthSupplier,
                invocationOf("read", new Class<?>[] {}, new Object[] {}));

        assertThat(decision.isGranted()).isFalse();
    }

    @Test // U25 — unauthenticated → deny
    void unauthenticated_deny() throws Exception {
        SecurityContextHolder.clearContext();

        AuthorizationDecision decision = manager.authorize(noopAuthSupplier,
                invocationOf("read", new Class<?>[] {}, new Object[] {}));

        assertThat(decision.isGranted()).isFalse();
    }

    @Test // U26 — unresolvable resource type (SpEL yields null) → deny
    void unresolvableResource_deny() throws Exception {
        AuthorizationDecision decision = manager.authorize(noopAuthSupplier,
                invocationOf("unresolvableType", new Class<?>[] {}, new Object[] {}));

        assertThat(decision.isGranted()).isFalse();
    }

    @Test // Slice B4 — roleResource override: the role is looked up on the PARENT catalog, while the
    // OPA context's resource (the queried policy) stays `category`.
    void roleResourceOverride_resolvesRoleOnParent() throws Exception {
        UUID catalogId = UUID.randomUUID();
        when(opaClient.decide(any())).thenReturn(OpaDecision.of(true));
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());

        ArgumentCaptor<AbacContext> ctx = ArgumentCaptor.forClass(AbacContext.class);
        manager.authorize(noopAuthSupplier,
                invocationOf("createChild", new Class<?>[] {UUID.class}, new Object[] {catalogId}));

        // The role is resolved on the parent catalog (the override), NOT on (category, null).
        verify(roleDefinitionSupplier).lookup("user-1", "catalog", catalogId.toString());
        // But the decided resource (the queried policy) is still `category`.
        verify(opaClient).decide(ctx.capture());
        assertThat(ctx.getValue().resource().type()).isEqualTo("category");
        assertThat(ctx.getValue().action()).isEqualTo("category:create");
    }

    @Test // Slice B4 — a declared roleResource override that resolves to null/blank → deny (fail-closed),
    // never silently falling back to the (category, null) lookup.
    void roleResourceOverride_unresolvable_deny() throws Exception {
        AuthorizationDecision decision = manager.authorize(noopAuthSupplier,
                invocationOf("createChildBadOverride", new Class<?>[] {UUID.class},
                        new Object[] {UUID.randomUUID()}));

        assertThat(decision.isGranted()).isFalse();
        // OPA is never asked when the override can't resolve.
        verify(opaClient, never()).decide(any());
    }

    @Test // unannotated method → DENY, not abstain. The manager is bound to an @OpaPreAuthorize pointcut,
    // so "matched but no annotation" is a wiring inconsistency — an abstain (null) would let the
    // interceptor proceed unenforced.
    void unannotated_denies() throws Exception {
        AuthorizationDecision decision = manager.authorize(noopAuthSupplier,
                invocationOf("unannotated", new Class<?>[] {}, new Object[] {}));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
    }

    @Test // a DECLARED resourceId whose SpEL evaluates to null must deny — silently degrading to a
    // type-level (id-less) check would skip per-id deny rules and per-resource role scoping (widen).
    void declaredResourceIdEvaluatesNull_deny() throws Exception {
        AuthorizationDecision decision = manager.authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {null}));

        assertThat(decision.isGranted()).isFalse();
        verify(opaClient, never()).decide(any());
    }

    @Test // U27 + U28 — RoleDefinitionSupplier consulted; AbacContext carries action + resource + role_definition
    void roleDefinitionReachesOpaInput() throws Exception {
        UUID productId = UUID.randomUUID();
        RoleDefinition roleDef = new RoleDefinition(
                "catalog-editor", Map.of("role_level", 20), Map.of("product", List.of("read", "write")));
        when(roleDefinitionSupplier.lookup("user-1", "product", productId.toString()))
                .thenReturn(Optional.of(roleDef));
        when(opaClient.decide(any())).thenReturn(OpaDecision.of(true));

        manager.authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {productId}));

        ArgumentCaptor<AbacContext> captor = ArgumentCaptor.forClass(AbacContext.class);
        verify(opaClient).decide(captor.capture());
        AbacContext sent = captor.getValue();
        assertThat(sent.action()).isEqualTo("product:write");
        assertThat(sent.resource().type()).isEqualTo("product");
        assertThat(sent.resource().id()).isEqualTo(productId.toString());
        assertThat(sent.roleDefinition()).isNotNull();
        assertThat(sent.roleDefinition().code()).isEqualTo("catalog-editor");
        assertThat(sent.roleDefinition().permissions().get("product")).contains("write");
        // and the supplier was consulted with the right coordinates
        verify(roleDefinitionSupplier).lookup("user-1", "product", productId.toString());
    }

    // --- B2: role-source outage vs authoritative no-role ---------------------

    @Test // B2 U2 — supplier throws RoleResolutionException (outage) → deny, OpaClient NEVER invoked
    // (no empty-role context is built, so the policy's realm fallback is never fed an outage input).
    void roleSourceOutage_failClosedDeny_neverCallsOpa() throws Exception {
        UUID productId = UUID.randomUUID();
        when(roleDefinitionSupplier.lookup("user-1", "product", productId.toString()))
                .thenThrow(new dev.dmitriikonovalov.opaabac.core.RoleResolutionException("source unavailable"));

        AuthorizationDecision decision = manager.authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {productId}));

        assertThat(decision.isGranted()).isFalse();
        verify(opaClient, never()).decide(any());
    }

    @Test // B2 U3 — the SIBLING (designed path unbroken): supplier returns Optional.empty() (authoritative
    // no-role) → the manager STILL builds a no-role_definition context and calls OPA once (the realm
    // fallback decides downstream). Proves B2 narrowed only the outage path, not the empty path.
    void authoritativeNoRole_buildsEmptyContext_andCallsOpa() throws Exception {
        UUID productId = UUID.randomUUID();
        when(roleDefinitionSupplier.lookup("user-1", "product", productId.toString()))
                .thenReturn(Optional.empty());
        when(opaClient.decide(any())).thenReturn(OpaDecision.of(true));

        AuthorizationDecision decision = manager.authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {productId}));

        assertThat(decision.isGranted()).isTrue();
        ArgumentCaptor<AbacContext> captor = ArgumentCaptor.forClass(AbacContext.class);
        verify(opaClient).decide(captor.capture());
        assertThat(captor.getValue().roleDefinition()).isNull(); // no role_definition → fallback eligible
    }

    @Test // U29 — resource() SpEL resolves an AbacResource instance
    void resourceInstance_resolvedFromSpel() throws Exception {
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.decide(any())).thenReturn(OpaDecision.of(true));
        SampleProduct product = new SampleProduct("p-42");

        manager.authorize(noopAuthSupplier,
                invocationOf("writeInstance", new Class<?>[] {SampleProduct.class}, new Object[] {product}));

        ArgumentCaptor<AbacContext> captor = ArgumentCaptor.forClass(AbacContext.class);
        verify(opaClient).decide(captor.capture());
        AbacContext.Resource resource = captor.getValue().resource();
        assertThat(resource.type()).isEqualTo("product");
        assertThat(resource.id()).isEqualTo("p-42");
        assertThat(resource.attributes()).containsEntry("status", "draft");
    }
}
