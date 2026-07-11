package dev.dmitriikonovalov.opaabac.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacResource;
import dev.dmitriikonovalov.opaabac.core.AbacResourceCache;
import dev.dmitriikonovalov.opaabac.core.AbacResourceResolver;
import dev.dmitriikonovalov.opaabac.core.AncestorChainSupplier;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import java.lang.reflect.Method;
import java.util.HashMap;
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

/**
 * Unit tests for {@link OpaPreAuthorizeAuthorizationManager}'s resource-resolution flow — QA cases
 * U5–U12 (the 409 advice is U13, {@code VersionConflictAdviceTest}).
 *
 * <p>The load-bearing cases: the byte-identical baseline without support (U5), the split failure
 * semantics in both directions (U8–U10), the governing-root role lookup (U7), and the cache as a pure
 * write-through output, never an input (U11/U12).
 */
class OpaPreAuthorizeAuthorizationManagerResolutionTest {

    private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final OpaClient opaClient = mock(OpaClient.class);
    private final RoleDefinitionSupplier roleDefinitionSupplier = mock(RoleDefinitionSupplier.class);
    private final AbacResourceResolver resolver = mock(AbacResourceResolver.class);
    private final AncestorChainSupplier chainSupplier = mock(AncestorChainSupplier.class);
    private final RecordingCache cache = new RecordingCache();

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

    // --- collaborator fixtures ------------------------------------------------

    /** An in-memory cache that records puts — the manager must never read it for a decision. */
    static final class RecordingCache implements AbacResourceCache {
        final Map<String, Object> store = new HashMap<>();
        int puts = 0;

        @Override
        public <T> Optional<T> get(String resourceType, String resourceId, Class<T> as) {
            Object value = store.get(resourceType + ":" + resourceId);
            return as.isInstance(value) ? Optional.of(as.cast(value)) : Optional.empty();
        }

        @Override
        public void put(String resourceType, String resourceId, Object resource) {
            puts++;
            store.put(resourceType + ":" + resourceId, resource);
        }
    }

    record SampleProduct(String id, Map<String, Object> attributes) implements AbacResource {
        @Override public String abacResourceType() { return "product"; }
        @Override public String abacResourceId() { return id; }
        @Override public Map<String, Object> abacAttributes() { return attributes; }
    }

    @SuppressWarnings("unused")
    static class SampleController {
        @OpaPreAuthorize(action = "product:write", resourceType = "'product'", resourceId = "#id")
        public void writeById(UUID id) {}

        @OpaPreAuthorize(action = "product:list", resourceType = "'product'")
        public void list() {}

        @OpaPreAuthorize(action = "product:write", resource = "#product")
        public void writeInstance(SampleProduct product) {}
    }

    private MethodInvocation invocationOf(String methodName, Class<?>[] paramTypes, Object[] args)
            throws Exception {
        SampleController target = new SampleController();
        Method method = SampleController.class.getMethod(methodName, paramTypes);
        MethodInvocation invocation = mock(MethodInvocation.class);
        lenient().when(invocation.getMethod()).thenReturn(method);
        lenient().when(invocation.getThis()).thenReturn(target);
        lenient().when(invocation.getArguments()).thenReturn(args);
        return invocation;
    }

    private OpaPreAuthorizeAuthorizationManager managerWithSupport() {
        return new OpaPreAuthorizeAuthorizationManager(opaClient, roleDefinitionSupplier,
                new ResourceResolutionSupport(resolver, chainSupplier, cache));
    }

    private OpaPreAuthorizeAuthorizationManager managerWithoutChainSupplier() {
        return new OpaPreAuthorizeAuthorizationManager(opaClient, roleDefinitionSupplier,
                new ResourceResolutionSupport(resolver, null, cache));
    }

    private AbacContext capturedContext() {
        ArgumentCaptor<AbacContext> captor = ArgumentCaptor.forClass(AbacContext.class);
        verify(opaClient).allow(captor.capture());
        return captor.getValue();
    }

    // --- U5: the byte-identical baseline --------------------------------------

    @Test // U5 — support absent: the serialized OPA input for an id'd check is string-equal to the
    // pre-5.97 manager's (golden comparison; serialized with the production mapper defaults)
    void supportAbsent_idCheckSerializesByteIdentical() throws Exception {
        OpaPreAuthorizeAuthorizationManager baseline =
                new OpaPreAuthorizeAuthorizationManager(opaClient, roleDefinitionSupplier);
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenReturn(true);

        baseline.authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {PRODUCT_ID}));

        String serialized = new ObjectMapper().writeValueAsString(capturedContext());
        assertThat(serialized).isEqualTo(
                "{\"subject\":{\"id\":\"user-1\",\"roles\":[\"catalog-editor\"],"
                        + "\"attributes\":{\"username\":\"alice\"}},"
                        + "\"action\":\"product:write\","
                        + "\"resource\":{\"type\":\"product\","
                        + "\"id\":\"11111111-1111-1111-1111-111111111111\",\"attributes\":{}},"
                        + "\"environment\":{}}");
        verify(resolver, never()).resolve(anyString(), anyString());
    }

    @Test // U5 — type-level checks never engage the resolver, even with support present
    void typeLevelCheck_neverEngagesResolver() throws Exception {
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenReturn(true);

        managerWithSupport().authorize(noopAuthSupplier,
                invocationOf("list", new Class<?>[] {}, new Object[] {}));

        verify(resolver, never()).resolve(anyString(), anyString());
        assertThat(capturedContext().resource().id()).isNull();
        assertThat(cache.puts).isZero(); // nothing resolved → nothing cached
    }

    // --- U6/U7: the full per-instance decision ---------------------------------

    @Test // U6 — resolved attributes AND ancestors (root-first) reach the context; OPA called once
    void resolvedInstance_attributesAndAncestorsReachContext() throws Exception {
        SampleProduct instance = new SampleProduct(PRODUCT_ID.toString(), Map.of("tags", Map.of("region", "emea")));
        when(resolver.resolve("product", PRODUCT_ID.toString())).thenReturn(Optional.of(instance));
        List<ParentRef> chain = List.of(new ParentRef("catalog", "cat-root"), new ParentRef("category", "cat-mid"));
        when(chainSupplier.ancestorsOf("product", PRODUCT_ID.toString())).thenReturn(chain);
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenReturn(true);

        managerWithSupport().authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {PRODUCT_ID}));

        verify(opaClient, times(1)).allow(any());
        AbacContext.Resource resource = capturedContext().resource();
        assertThat(resource.type()).isEqualTo("product");
        assertThat(resource.id()).isEqualTo(PRODUCT_ID.toString());
        assertThat(resource.attributes()).isEqualTo(Map.of("tags", Map.of("region", "emea")));
        assertThat(resource.ancestors()).containsExactlyElementsOf(chain); // root-first, leaf-excluded
    }

    @Test // U7 — with ancestors, the role is looked up ONCE on the governing root (ancestors[0])
    void roleLookedUpOnceOnGoverningRoot() throws Exception {
        SampleProduct instance = new SampleProduct(PRODUCT_ID.toString(), Map.of());
        when(resolver.resolve("product", PRODUCT_ID.toString())).thenReturn(Optional.of(instance));
        when(chainSupplier.ancestorsOf("product", PRODUCT_ID.toString()))
                .thenReturn(List.of(new ParentRef("catalog", "cat-root"), new ParentRef("category", "cat-mid")));
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenReturn(true);

        managerWithSupport().authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {PRODUCT_ID}));

        verify(roleDefinitionSupplier, times(1)).lookup(any(), any(), any());
        verify(roleDefinitionSupplier).lookup("user-1", "catalog", "cat-root");
    }

    @Test // U7 — with an empty chain, the role is looked up on the leaf
    void roleLookedUpOnLeafWhenChainEmpty() throws Exception {
        SampleProduct instance = new SampleProduct(PRODUCT_ID.toString(), Map.of());
        when(resolver.resolve("product", PRODUCT_ID.toString())).thenReturn(Optional.of(instance));
        when(chainSupplier.ancestorsOf("product", PRODUCT_ID.toString())).thenReturn(List.of());
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenReturn(true);

        managerWithSupport().authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {PRODUCT_ID}));

        verify(roleDefinitionSupplier, times(1)).lookup(any(), any(), any());
        verify(roleDefinitionSupplier).lookup("user-1", "product", PRODUCT_ID.toString());
    }

    // --- U8/U9: instance failure → DENY (never an attribute-less context) ------

    @Test // U8 — resolver empty → deny; OPA never invoked; nothing cached
    void resolverEmpty_deniesWithoutOpaCall() throws Exception {
        when(resolver.resolve("product", PRODUCT_ID.toString())).thenReturn(Optional.empty());

        AuthorizationDecision decision = managerWithSupport().authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {PRODUCT_ID}));

        assertThat(decision.isGranted()).isFalse();
        verify(opaClient, never()).allow(any());
        assertThat(cache.puts).isZero();
    }

    @Test // U9 — resolver throws → deny; OPA never invoked; nothing cached (instance ≠ ancestor failure)
    void resolverThrows_deniesWithoutOpaCall() throws Exception {
        when(resolver.resolve("product", PRODUCT_ID.toString()))
                .thenThrow(new RuntimeException("repository unavailable"));

        AuthorizationDecision decision = managerWithSupport().authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {PRODUCT_ID}));

        assertThat(decision.isGranted()).isFalse();
        verify(opaClient, never()).allow(any());
        assertThat(cache.puts).isZero();
    }

    // --- U10: ancestor failure → collapse, never deny ---------------------------

    @Test // U10 — chain supplier throws → decision proceeds with ancestors == [], role on the leaf
    void chainThrows_collapsesToDirectOnly() throws Exception {
        SampleProduct instance = new SampleProduct(PRODUCT_ID.toString(), Map.of("status", "live"));
        when(resolver.resolve("product", PRODUCT_ID.toString())).thenReturn(Optional.of(instance));
        when(chainSupplier.ancestorsOf("product", PRODUCT_ID.toString()))
                .thenThrow(new RuntimeException("walk failed"));
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenReturn(true);

        AuthorizationDecision decision = managerWithSupport().authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {PRODUCT_ID}));

        assertThat(decision.isGranted()).isTrue(); // the failure narrowed, it did not deny
        AbacContext.Resource resource = capturedContext().resource();
        assertThat(resource.ancestors()).isEmpty(); // never a partial chain
        assertThat(resource.attributes()).isEqualTo(Map.of("status", "live")); // instance still resolved
        verify(roleDefinitionSupplier).lookup("user-1", "product", PRODUCT_ID.toString());
    }

    @Test // U10 — supplier null (no hierarchy configured) → empty chain, role on the leaf
    void nullChainSupplier_emptyChain() throws Exception {
        SampleProduct instance = new SampleProduct(PRODUCT_ID.toString(), Map.of());
        when(resolver.resolve("product", PRODUCT_ID.toString())).thenReturn(Optional.of(instance));
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenReturn(true);

        managerWithoutChainSupplier().authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {PRODUCT_ID}));

        assertThat(capturedContext().resource().ancestors()).isEmpty();
        verify(roleDefinitionSupplier).lookup("user-1", "product", PRODUCT_ID.toString());
    }

    // --- U11/U12: the cache is write-through output, never an input -------------

    @Test // U11 — OPA allow → put once with the resolved instance; deny → no put
    void cachePutOnAllowOnly() throws Exception {
        SampleProduct instance = new SampleProduct(PRODUCT_ID.toString(), Map.of());
        when(resolver.resolve("product", PRODUCT_ID.toString())).thenReturn(Optional.of(instance));
        when(chainSupplier.ancestorsOf("product", PRODUCT_ID.toString())).thenReturn(List.of());
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());

        when(opaClient.allow(any())).thenReturn(false);
        managerWithSupport().authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {PRODUCT_ID}));
        assertThat(cache.puts).isZero(); // deny puts nothing

        when(opaClient.allow(any())).thenReturn(true);
        managerWithSupport().authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {PRODUCT_ID}));
        assertThat(cache.puts).isEqualTo(1);
        assertThat(cache.get("product", PRODUCT_ID.toString(), SampleProduct.class)).contains(instance);
    }

    @Test // U11 — the resource()-SpEL branch puts its instance on allow (decision inputs unchanged)
    void resourceSpelBranch_putsOnAllow() throws Exception {
        SampleProduct product = new SampleProduct("p-42", Map.of("status", "draft"));
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenReturn(true);

        managerWithSupport().authorize(noopAuthSupplier,
                invocationOf("writeInstance", new Class<?>[] {SampleProduct.class}, new Object[] {product}));

        verify(resolver, never()).resolve(anyString(), anyString()); // caller already holds the instance
        AbacContext.Resource resource = capturedContext().resource();
        assertThat(resource.attributes()).isEqualTo(Map.of("status", "draft")); // inputs unchanged
        assertThat(resource.ancestors()).isEmpty();
        assertThat(cache.get("product", "p-42", SampleProduct.class)).contains(product);
    }

    @Test // U12 — the cache is never an input: a pre-populated different instance is ignored; the
    // manager resolves fresh and decides on the resolver's instance
    void cacheNeverAnInput() throws Exception {
        SampleProduct stale = new SampleProduct(PRODUCT_ID.toString(), Map.of("tags", Map.of("region", "apac")));
        cache.put("product", PRODUCT_ID.toString(), stale);
        cache.puts = 0;

        SampleProduct fresh = new SampleProduct(PRODUCT_ID.toString(), Map.of("tags", Map.of("region", "emea")));
        when(resolver.resolve("product", PRODUCT_ID.toString())).thenReturn(Optional.of(fresh));
        when(chainSupplier.ancestorsOf("product", PRODUCT_ID.toString())).thenReturn(List.of());
        when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(opaClient.allow(any())).thenReturn(true);

        managerWithSupport().authorize(noopAuthSupplier,
                invocationOf("writeById", new Class<?>[] {UUID.class}, new Object[] {PRODUCT_ID}));

        verify(resolver).resolve("product", PRODUCT_ID.toString()); // resolved fresh despite the cache
        assertThat(capturedContext().resource().attributes())
                .isEqualTo(Map.of("tags", Map.of("region", "emea")));
    }
}
