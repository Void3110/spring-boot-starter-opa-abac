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
 * Production-tier T3 (U13, U14, U15): manager-side root-attribute enrichment (ADR 0032).
 *
 * <p>The rule under test is one rule for both paths: the field carries the attributes of the
 * <b>governing target the manager already computes</b> — the ancestor chain's root on the instance path,
 * the {@code roleResource} override target on the type-level child gates — and stays <b>absent</b>
 * whenever nothing proved it.
 *
 * <p>The failure cases are the point of the ticket. An enrichment failure must land on <em>absent</em>,
 * never on {@code {}} (which would read as "the root is untagged" and open a supervised tier), and never
 * on an exception escaping the manager (which would turn a tag lookup into a member-facing outage).
 */
class OpaPreAuthorizeRootAttributeEnrichmentTest {

    private static final UUID CATEGORY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CATALOG_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final OpaClient opaClient = mock(OpaClient.class);
    private final RoleDefinitionSupplier roleDefinitionSupplier = mock(RoleDefinitionSupplier.class);
    private final AbacResourceResolver resolver = mock(AbacResourceResolver.class);
    private final AncestorChainSupplier chainSupplier = mock(AncestorChainSupplier.class);
    private final RecordingCache cache = new RecordingCache();

    private final Supplier<Authentication> noopAuthSupplier = () -> null;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(
                new AbacContext.Subject("sup-anna", List.of(), Map.of())));
        lenient().when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(opaClient.allow(any())).thenReturn(true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // --- fixtures --------------------------------------------------------------

    static final class RecordingCache implements AbacResourceCache {
        final Map<String, Object> store = new HashMap<>();

        @Override
        public <T> Optional<T> get(String resourceType, String resourceId, Class<T> as) {
            Object value = store.get(resourceType + ":" + resourceId);
            return as.isInstance(value) ? Optional.of(as.cast(value)) : Optional.empty();
        }

        @Override
        public void put(String resourceType, String resourceId, Object resource) {
            store.put(resourceType + ":" + resourceId, resource);
        }
    }

    record SampleResource(String type, String id, Map<String, Object> attributes) implements AbacResource {
        @Override public String abacResourceType() { return type; }
        @Override public String abacResourceId() { return id; }
        @Override public Map<String, Object> abacAttributes() { return attributes; }
    }

    @SuppressWarnings("unused")
    static class SampleController {
        @OpaPreAuthorize(action = "category:view", resourceType = "'category'", resourceId = "#categoryId")
        public void getCategory(UUID categoryId) {}

        @OpaPreAuthorize(action = "catalog:view", resourceType = "'catalog'", resourceId = "#catalogId")
        public void getCatalog(UUID catalogId) {}

        @OpaPreAuthorize(action = "category:list", resourceType = "'category'",
                roleResourceType = "'catalog'", roleResourceId = "#catalogId")
        public void listCategories(UUID catalogId) {}

        @OpaPreAuthorize(action = "category:list", resourceType = "'category'")
        public void listCategoriesUngoverned() {}

        @OpaPreAuthorize(action = "category:list", resourceType = "'category'",
                roleResourceType = "'catalog'", roleResourceId = "#missing")
        public void listCategoriesUnresolvableOverride(UUID catalogId) {}
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

    private OpaPreAuthorizeAuthorizationManager manager() {
        return new OpaPreAuthorizeAuthorizationManager(opaClient, roleDefinitionSupplier,
                new ResourceResolutionSupport(resolver, chainSupplier, cache));
    }

    private AbacContext capturedContext() {
        ArgumentCaptor<AbacContext> captor = ArgumentCaptor.forClass(AbacContext.class);
        verify(opaClient).allow(captor.capture());
        return captor.getValue();
    }

    /** A category whose ancestor chain roots at the catalog — the tier's shape. */
    private void givenCategoryUnderCatalog() {
        when(resolver.resolve("category", CATEGORY_ID.toString()))
                .thenReturn(Optional.of(new SampleResource("category", CATEGORY_ID.toString(), Map.of())));
        when(chainSupplier.ancestorsOf("category", CATEGORY_ID.toString()))
                .thenReturn(List.of(new ParentRef("catalog", CATALOG_ID.toString())));
    }

    private void givenCatalogTagged(Map<String, Object> tags) {
        when(resolver.resolve("catalog", CATALOG_ID.toString()))
                .thenReturn(Optional.of(new SampleResource("catalog", CATALOG_ID.toString(), tags)));
    }

    private MethodInvocation getCategory() throws Exception {
        return invocationOf("getCategory", new Class<?>[] {UUID.class}, new Object[] {CATEGORY_ID});
    }

    // --- U13: the instance path ------------------------------------------------

    @Test
    void governingRootDistinctFromLeaf_threadsItsAttributes() throws Exception {
        givenCategoryUnderCatalog();
        givenCatalogTagged(Map.of("env", "production"));

        manager().authorize(noopAuthSupplier, getCategory());

        assertThat(capturedContext().resource().rootAttributes())
                .containsExactlyEntriesOf(Map.of("env", "production"));
    }

    @Test
    void anUntaggedRootIsAnEmptyMapNotAbsent() throws Exception {
        givenCategoryUnderCatalog();
        givenCatalogTagged(Map.of());

        manager().authorize(noopAuthSupplier, getCategory());

        // The distinction the whole contract rests on: fetched-and-untagged, not never-found-out.
        assertThat(capturedContext().resource().rootAttributes()).isNotNull().isEmpty();
    }

    @Test
    void leafIsItsOwnRoot_absent() throws Exception {
        when(resolver.resolve("catalog", CATALOG_ID.toString()))
                .thenReturn(Optional.of(new SampleResource(
                        "catalog", CATALOG_ID.toString(), Map.of("env", "production"))));
        when(chainSupplier.ancestorsOf("catalog", CATALOG_ID.toString())).thenReturn(List.of());

        manager().authorize(noopAuthSupplier,
                invocationOf("getCatalog", new Class<?>[] {UUID.class}, new Object[] {CATALOG_ID}));

        // A root's own read is never enriched: its own attributes already carry its tags, and gating a
        // root's read on "root attributes" is exactly what ADR 0030 §1 keeps out.
        assertThat(capturedContext().resource().rootAttributes()).isNull();
        verify(resolver, times(1)).resolve(anyString(), anyString());
    }

    @Test
    void rootResolvesEmpty_absent() throws Exception {
        givenCategoryUnderCatalog();
        when(resolver.resolve("catalog", CATALOG_ID.toString())).thenReturn(Optional.empty());

        manager().authorize(noopAuthSupplier, getCategory());

        assertThat(capturedContext().resource().rootAttributes()).isNull();
    }

    @Test
    void rootResolverThrows_absentAndNoExceptionEscapes() throws Exception {
        givenCategoryUnderCatalog();
        when(resolver.resolve("catalog", CATALOG_ID.toString()))
                .thenThrow(new IllegalStateException("database on fire"));

        AuthorizationDecision decision = manager().authorize(noopAuthSupplier, getCategory());

        // The member half of the fail-closed rule: the request proceeds on its own merits, unchanged —
        // the tier is simply unproven, and only a supervised policy clause cares.
        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
        assertThat(capturedContext().resource().rootAttributes()).isNull();
    }

    @Test
    void aRootReportingNullAttributes_absentNotEmpty() throws Exception {
        givenCategoryUnderCatalog();
        when(resolver.resolve("catalog", CATALOG_ID.toString()))
                .thenReturn(Optional.of(new SampleResource("catalog", CATALOG_ID.toString(), null)));

        manager().authorize(noopAuthSupplier, getCategory());

        // The direction matters: "the root told us nothing" is unproven (closed), not untagged (open).
        assertThat(capturedContext().resource().rootAttributes()).isNull();
    }

    @Test
    void noResolutionSupport_absent() throws Exception {
        OpaPreAuthorizeAuthorizationManager bare =
                new OpaPreAuthorizeAuthorizationManager(opaClient, roleDefinitionSupplier);

        bare.authorize(noopAuthSupplier, getCategory());

        assertThat(capturedContext().resource().rootAttributes()).isNull();
        verify(resolver, never()).resolve(anyString(), anyString());
    }

    // --- U14: the type-level path ----------------------------------------------

    @Test
    void resolvableOverride_threadsTheOverrideTargetsAttributes() throws Exception {
        givenCatalogTagged(Map.of("env", "staging"));

        manager().authorize(noopAuthSupplier,
                invocationOf("listCategories", new Class<?>[] {UUID.class}, new Object[] {CATALOG_ID}));

        AbacContext context = capturedContext();
        assertThat(context.resource().id()).isNull(); // the coarse gate: still a type-level decision
        assertThat(context.resource().rootAttributes())
                .containsExactlyEntriesOf(Map.of("env", "staging"));
    }

    @Test
    void noOverride_absentAndTheResolverIsNeverEngaged() throws Exception {
        manager().authorize(noopAuthSupplier,
                invocationOf("listCategoriesUngoverned", new Class<?>[] {}, new Object[] {}));

        assertThat(capturedContext().resource().rootAttributes()).isNull();
        verify(resolver, never()).resolve(anyString(), anyString());
    }

    @Test
    void declaredButUnresolvableOverride_deniesBeforeEnrichmentIsAttempted() throws Exception {
        AuthorizationDecision decision = manager().authorize(noopAuthSupplier,
                invocationOf("listCategoriesUnresolvableOverride",
                        new Class<?>[] {UUID.class}, new Object[] {CATALOG_ID}));

        // The pre-existing fail-closed branch, asserted NOT regressed: enrichment must not turn a deny
        // into an allow-with-absent-tier, and OPA must not be asked at all.
        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
        verify(opaClient, never()).allow(any());
        verify(resolver, never()).resolve(anyString(), anyString());
    }

    // --- U15: one root resolve per request --------------------------------------

    @Test
    void theRootResolveIsMemoizedAcrossChecksInOneRequest() throws Exception {
        givenCategoryUnderCatalog();
        givenCatalogTagged(Map.of("env", "staging"));
        OpaPreAuthorizeAuthorizationManager manager = manager();

        // The gate (type-level, override) and then the instance check — the shape of a real request.
        manager.authorize(noopAuthSupplier,
                invocationOf("listCategories", new Class<?>[] {UUID.class}, new Object[] {CATALOG_ID}));
        manager.authorize(noopAuthSupplier, getCategory());

        verify(resolver, times(1)).resolve("catalog", CATALOG_ID.toString());

        ArgumentCaptor<AbacContext> captor = ArgumentCaptor.forClass(AbacContext.class);
        verify(opaClient, times(2)).allow(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(context -> assertThat(context.resource().rootAttributes())
                        .containsExactlyEntriesOf(Map.of("env", "staging")));
    }
}
