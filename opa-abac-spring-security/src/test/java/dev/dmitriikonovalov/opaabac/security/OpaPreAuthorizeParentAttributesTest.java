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
import dev.dmitriikonovalov.opaabac.core.OpaDecision;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * TAG-GATED-CREATE T2 (U16–U22): the placement-parent enrichment and the declared payload on a
 * type-level decision (ADR 0034).
 *
 * <p>The edges are the point. A <b>declaration</b> the manager cannot honor (a non-map or null-valued
 * payload, a payload on an instance form, half a parent pair, a parent expression resolving to
 * null/blank) must <em>deny before OPA is asked</em> — never a silent "no parent" or "no payload",
 * which is exactly the widening the placement gate exists to close. A parent that fails to
 * <b>resolve</b> (empty, throws, no resolution support) must land on <em>absent</em>, never on
 * {@code {}} (which would read as "the parent is untagged") and never on an exception out of the
 * manager. Both fields keep their own declaration: on a top-level category create the placement
 * parent <em>is</em> the governing root, and the memo makes the second resolve a cache hit.
 */
class OpaPreAuthorizeParentAttributesTest {

    private static final UUID CATALOG_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CATEGORY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PARENT_CATEGORY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Map<String, Object> CATALOG_TAGS = Map.of("region", List.of("emea"));
    private static final Map<String, Object> CATEGORY_TAGS = Map.of("region", List.of("apac"));
    private static final Map<String, Object> PAYLOAD = Map.of("region", List.of("emea"));

    private final OpaClient opaClient = mock(OpaClient.class);
    private final RoleDefinitionSupplier roleDefinitionSupplier = mock(RoleDefinitionSupplier.class);
    private final AbacResourceResolver resolver = mock(AbacResourceResolver.class);
    private final AncestorChainSupplier chainSupplier = mock(AncestorChainSupplier.class);
    private final RecordingCache cache = new RecordingCache();
    private final Supplier<Authentication> noopAuthSupplier = () -> null;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(
                new AbacContext.Subject("alice", List.of(), Map.of())));
        lenient().when(roleDefinitionSupplier.lookup(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(opaClient.decide(any())).thenReturn(OpaDecision.of(true));
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

    /** The shape of a generated request DTO: bean getters, a nullable parent, a nullable tag map. */
    public static final class SampleRequest {
        private final UUID parentId;
        private final Map<String, Object> tags;

        SampleRequest(UUID parentId, Map<String, Object> tags) {
            this.parentId = parentId;
            this.tags = tags;
        }

        public UUID getParentId() { return parentId; }
        public Map<String, Object> getTags() { return tags; }
    }

    // Pointcut targets only: the gate decides before any body runs, which is why none of these have one.
    @SuppressWarnings({"unused", "java:S1186"})
    static class SampleController {
        /** The catalog service's category create, exactly as T3 declares it. */
        @OpaPreAuthorize(action = "category:create", resourceType = "'category'",
                roleResourceType = "'catalog'", roleResourceId = "#catalogId",
                parentResourceType = "#request.parentId != null ? 'category' : 'catalog'",
                parentResourceId = "#request.parentId != null ? #request.parentId : #catalogId",
                attributes = "#request.tags")
        public void createCategory(UUID catalogId, SampleRequest request) {}

        /** The product create: the role on the catalog, the placement parent the category. */
        @OpaPreAuthorize(action = "product:create", resourceType = "'product'",
                roleResourceType = "'catalog'", roleResourceId = "#catalogId",
                parentResourceType = "'category'", parentResourceId = "#categoryId",
                attributes = "#request.tags")
        public void createProduct(UUID catalogId, UUID categoryId, SampleRequest request) {}

        @OpaPreAuthorize(action = "category:create", resourceType = "'category'",
                roleResourceType = "'catalog'", roleResourceId = "#catalogId",
                parentResourceType = "'catalog'", parentResourceId = "#catalogId",
                attributes = "'not-a-map'")
        public void createWithStringAttributes(UUID catalogId) {}

        @OpaPreAuthorize(action = "category:create", resourceType = "'category'",
                roleResourceType = "'catalog'", roleResourceId = "#catalogId",
                parentResourceType = "'catalog'", parentResourceId = "#catalogId",
                attributes = "42")
        public void createWithNumberAttributes(UUID catalogId) {}

        @OpaPreAuthorize(action = "category:view", resourceType = "'category'", resourceId = "#categoryId",
                attributes = "#request.tags")
        public void conflictWithResourceId(UUID categoryId, SampleRequest request) {}

        @OpaPreAuthorize(action = "category:view", resource = "#resource", attributes = "#request.tags")
        public void conflictWithResourceObject(SampleResource resource, SampleRequest request) {}

        @OpaPreAuthorize(action = "category:create", resourceType = "'category'",
                roleResourceType = "'catalog'", roleResourceId = "#catalogId",
                parentResourceType = "'catalog'", parentResourceId = "#missing",
                attributes = "#request.tags")
        public void createWithUnboundParent(UUID catalogId, SampleRequest request) {}

        @OpaPreAuthorize(action = "category:create", resourceType = "'category'",
                roleResourceType = "'catalog'", roleResourceId = "#catalogId",
                parentResourceType = "'catalog'", parentResourceId = "''")
        public void createWithBlankParent(UUID catalogId) {}

        @OpaPreAuthorize(action = "category:create", resourceType = "'category'",
                roleResourceType = "'catalog'", roleResourceId = "#catalogId",
                parentResourceType = "'catalog'")
        public void createWithHalfParent(UUID catalogId) {}

        /** The pre-ADR-0034 shape: a type-level gate with the role-resource override and nothing else. */
        @OpaPreAuthorize(action = "category:create", resourceType = "'category'",
                roleResourceType = "'catalog'", roleResourceId = "#catalogId")
        public void createUndeclared(UUID catalogId) {}
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
        verify(opaClient).decide(captor.capture());
        return captor.getValue();
    }

    private void givenCatalogTagged(Map<String, Object> tags) {
        when(resolver.resolve("catalog", CATALOG_ID.toString()))
                .thenReturn(Optional.of(new SampleResource("catalog", CATALOG_ID.toString(), tags)));
    }

    private void givenCategoryTagged(UUID id, Map<String, Object> tags) {
        when(resolver.resolve("category", id.toString()))
                .thenReturn(Optional.of(new SampleResource("category", id.toString(), tags)));
    }

    private MethodInvocation createCategory(SampleRequest request) throws Exception {
        return invocationOf("createCategory", new Class<?>[] {UUID.class, SampleRequest.class},
                new Object[] {CATALOG_ID, request});
    }

    private MethodInvocation createProduct(SampleRequest request) throws Exception {
        return invocationOf("createProduct", new Class<?>[] {UUID.class, UUID.class, SampleRequest.class},
                new Object[] {CATALOG_ID, CATEGORY_ID, request});
    }

    private MethodInvocation typeLevel(String methodName) throws Exception {
        return invocationOf(methodName, new Class<?>[] {UUID.class}, new Object[] {CATALOG_ID});
    }

    // --- U16: declared attributes and the ternary parent -------------------------

    @Test
    void declaredAttributesReachTheTypeLevelDecision() throws Exception {
        givenCatalogTagged(CATALOG_TAGS);

        manager().authorize(noopAuthSupplier, createCategory(new SampleRequest(null, PAYLOAD)));

        AbacContext.Resource resource = capturedContext().resource();
        assertThat(resource.id()).isNull(); // still a type-level decision
        assertThat(resource.attributes()).containsExactlyEntriesOf(PAYLOAD);
    }

    @Test
    void aNullPayloadBecomesAnEmptyMap() throws Exception {
        givenCatalogTagged(CATALOG_TAGS);

        manager().authorize(noopAuthSupplier, createCategory(new SampleRequest(null, null)));

        assertThat(capturedContext().resource().attributes()).isEmpty();
    }

    @Test
    void theTernaryParentResolvesToTheCatalogWhenNoParentIsSet() throws Exception {
        givenCatalogTagged(CATALOG_TAGS);

        manager().authorize(noopAuthSupplier, createCategory(new SampleRequest(null, PAYLOAD)));

        verify(resolver).resolve("catalog", CATALOG_ID.toString());
        verify(resolver, never()).resolve(org.mockito.ArgumentMatchers.eq("category"), anyString());
        assertThat(capturedContext().resource().parentAttributes()).containsExactlyEntriesOf(CATALOG_TAGS);
    }

    @Test
    void theTernaryParentResolvesToTheParentCategoryWhenSet() throws Exception {
        givenCatalogTagged(CATALOG_TAGS);
        givenCategoryTagged(PARENT_CATEGORY_ID, CATEGORY_TAGS);

        manager().authorize(noopAuthSupplier, createCategory(new SampleRequest(PARENT_CATEGORY_ID, PAYLOAD)));

        verify(resolver).resolve("category", PARENT_CATEGORY_ID.toString());
        AbacContext.Resource resource = capturedContext().resource();
        assertThat(resource.parentAttributes()).containsExactlyEntriesOf(CATEGORY_TAGS); // the parent category
        assertThat(resource.rootAttributes()).containsExactlyEntriesOf(CATALOG_TAGS);    // still the catalog
    }

    // --- U17: a payload that is not a map denies --------------------------------

    /**
     * U17 + U20: a declaration the manager cannot honor denies before OPA is asked — a payload that is
     * not a map (a string, a number), a parent expression resolving to blank, half a parent pair.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "createWithStringAttributes",
        "createWithNumberAttributes",
        "createWithBlankParent",
        "createWithHalfParent"})
    void aDeclarationThatCannotBeHonoredDeniesBeforeOpaIsAsked(String gate) throws Exception {
        AuthorizationDecision decision = manager().authorize(noopAuthSupplier, typeLevel(gate));

        assertThat(decision.isGranted()).isFalse();
        verify(opaClient, never()).decide(any());
        verify(resolver, never()).resolve(anyString(), anyString());
    }

    @Test
    void aNullValuedPayloadDeniesBeforeOpaIsAsked() throws Exception {
        Map<String, Object> tags = new HashMap<>();
        tags.put("region", null);

        AuthorizationDecision decision =
                manager().authorize(noopAuthSupplier, createCategory(new SampleRequest(null, tags)));

        assertThat(decision.isGranted()).isFalse();
        verify(opaClient, never()).decide(any());
    }

    // --- U18: a payload on an instance form is a declaration conflict ----------

    @Test
    void attributesDeclaredWithResourceIdDeny() throws Exception {
        givenCategoryTagged(CATEGORY_ID, CATEGORY_TAGS);

        AuthorizationDecision decision = manager().authorize(noopAuthSupplier,
                invocationOf("conflictWithResourceId", new Class<?>[] {UUID.class, SampleRequest.class},
                        new Object[] {CATEGORY_ID, new SampleRequest(null, PAYLOAD)}));

        assertThat(decision.isGranted()).isFalse();
        verify(opaClient, never()).decide(any());
        verify(resolver, never()).resolve(anyString(), anyString());
    }

    @Test
    void attributesDeclaredWithResourceObjectDeny() throws Exception {
        AuthorizationDecision decision = manager().authorize(noopAuthSupplier,
                invocationOf("conflictWithResourceObject",
                        new Class<?>[] {SampleResource.class, SampleRequest.class},
                        new Object[] {new SampleResource("category", CATEGORY_ID.toString(), CATEGORY_TAGS),
                                new SampleRequest(null, PAYLOAD)}));

        assertThat(decision.isGranted()).isFalse();
        verify(opaClient, never()).decide(any());
    }

    // --- U19: the parent is resolved and memoized --------------------------------

    @Test
    void aTopLevelCategoryCreateResolvesTheCatalogOnceAndFillsBothFields() throws Exception {
        givenCatalogTagged(CATALOG_TAGS);

        manager().authorize(noopAuthSupplier, createCategory(new SampleRequest(null, PAYLOAD)));

        // The placement parent IS the governing root: one resolver call, the memo serves the second.
        verify(resolver, times(1)).resolve("catalog", CATALOG_ID.toString());
        AbacContext.Resource resource = capturedContext().resource();
        assertThat(resource.rootAttributes()).containsExactlyEntriesOf(CATALOG_TAGS);
        assertThat(resource.parentAttributes()).containsExactlyEntriesOf(CATALOG_TAGS);
    }

    @Test
    void aProductCreateCarriesTheCategoryNotTheCatalog() throws Exception {
        givenCatalogTagged(CATALOG_TAGS);
        givenCategoryTagged(CATEGORY_ID, CATEGORY_TAGS);

        manager().authorize(noopAuthSupplier, createProduct(new SampleRequest(null, PAYLOAD)));

        AbacContext.Resource resource = capturedContext().resource();
        assertThat(resource.type()).isEqualTo("product");
        assertThat(resource.parentAttributes()).containsExactlyEntriesOf(CATEGORY_TAGS);
        assertThat(resource.rootAttributes()).containsExactlyEntriesOf(CATALOG_TAGS);
        assertThat(resource.attributes()).containsExactlyEntriesOf(PAYLOAD);
    }

    // --- U20: a declaration that cannot be honored denies ------------------------

    @Test
    void anUnboundParentExpressionDenies() throws Exception {
        AuthorizationDecision decision = manager().authorize(noopAuthSupplier,
                invocationOf("createWithUnboundParent", new Class<?>[] {UUID.class, SampleRequest.class},
                        new Object[] {CATALOG_ID, new SampleRequest(null, PAYLOAD)}));

        assertThat(decision.isGranted()).isFalse();
        verify(opaClient, never()).decide(any());
    }

    // --- U21: a parent that fails to resolve is absent, never an exception -------

    @Test
    void parentResolvesEmpty_absent() throws Exception {
        when(resolver.resolve("catalog", CATALOG_ID.toString())).thenReturn(Optional.empty());

        AuthorizationDecision decision =
                manager().authorize(noopAuthSupplier, createCategory(new SampleRequest(null, PAYLOAD)));

        assertThat(decision.isGranted()).isTrue(); // the policy decides what absence means — not the manager
        AbacContext.Resource resource = capturedContext().resource();
        assertThat(resource.parentAttributes()).isNull();
        assertThat(resource.attributes()).containsExactlyEntriesOf(PAYLOAD); // the payload still rides
    }

    @Test
    void parentResolverThrows_absentAndNoExceptionEscapes() throws Exception {
        when(resolver.resolve("catalog", CATALOG_ID.toString()))
                .thenThrow(new IllegalStateException("database on fire"));

        AuthorizationDecision decision =
                manager().authorize(noopAuthSupplier, createCategory(new SampleRequest(null, PAYLOAD)));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isTrue();
        assertThat(capturedContext().resource().parentAttributes()).isNull();
    }

    @Test
    void noResolutionSupport_absentAndTheResolverIsNeverEngaged() throws Exception {
        OpaPreAuthorizeAuthorizationManager bare =
                new OpaPreAuthorizeAuthorizationManager(opaClient, roleDefinitionSupplier);

        bare.authorize(noopAuthSupplier, createCategory(new SampleRequest(null, PAYLOAD)));

        AbacContext.Resource resource = capturedContext().resource();
        assertThat(resource.parentAttributes()).isNull();
        assertThat(resource.attributes()).containsExactlyEntriesOf(PAYLOAD);
        verify(resolver, never()).resolve(anyString(), anyString());
    }

    // --- U22: untagged is {}, and an undeclared gate is byte-identical to before --

    @Test
    void anUntaggedParentIsAnEmptyMapNotAbsent() throws Exception {
        givenCatalogTagged(Map.of());

        manager().authorize(noopAuthSupplier, createCategory(new SampleRequest(null, PAYLOAD)));

        assertThat(capturedContext().resource().parentAttributes()).isNotNull().isEmpty();
    }

    @Test
    void aGateWithNoDeclarationCarriesNoParentAndAnEmptyPayload() throws Exception {
        givenCatalogTagged(CATALOG_TAGS);

        manager().authorize(noopAuthSupplier, typeLevel("createUndeclared"));

        AbacContext.Resource resource = capturedContext().resource();
        assertThat(resource.parentAttributes()).isNull();
        assertThat(resource.attributes()).isEmpty();
        assertThat(resource.rootAttributes()).containsExactlyEntriesOf(CATALOG_TAGS); // ADR 0032, unchanged
    }
}
