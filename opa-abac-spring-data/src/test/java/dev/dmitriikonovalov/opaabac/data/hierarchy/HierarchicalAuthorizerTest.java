package dev.dmitriikonovalov.opaabac.data.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacDataObject;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link HierarchicalAuthorizer} with a mocked {@link OpaClient} +
 * {@link RoleDefinitionSupplier} and a stub {@link AncestorResolver} (QA cases U5, U6, I10-I13). Pins the
 * decisive behaviors: the role is resolved <b>on the root</b>, the chain reaches OPA root-first/leaf-excluded,
 * and every fail path lands on <b>direct-grant-only or deny</b> — never wider.
 */
class HierarchicalAuthorizerTest {

    private static final String CATALOG = "11111111-1111-1111-1111-111111111111";
    private static final String CATEGORY = "22222222-2222-2222-2222-222222222222";
    private static final String PRODUCT = "33333333-3333-3333-3333-333333333333";

    private static final ParentRef CATALOG_REF = new ParentRef("catalog", CATALOG);
    private static final ParentRef CATEGORY_REF = new ParentRef("category", CATEGORY);

    private final OpaClient opaClient = mock(OpaClient.class);
    private final RoleDefinitionSupplier supplier = mock(RoleDefinitionSupplier.class);

    private static AbacDataObject product() {
        return new AbacDataObject() {
            @Override
            public String abacResourceType() {
                return "product";
            }

            @Override
            public String abacResourceId() {
                return PRODUCT;
            }

            @Override
            public Map<String, Object> abacAttributes() {
                return Map.of("sku", "X-1");
            }
        };
    }

    private static AbacContext.Subject subject() {
        return new AbacContext.Subject("user-1", List.of(), Map.of());
    }

    private HierarchicalAuthorizer authorizer(AncestorResolver resolver) {
        return new HierarchicalAuthorizer(resolver, supplier, opaClient);
    }

    private static RoleDefinition catalogViewer() {
        return new RoleDefinition("catalog-viewer", Map.of(), Map.of("catalog", List.of("read")));
    }

    @Test // I10 + U5 + U6 — a Catalog grant authorizes a deep Product: role on the ROOT, ancestors carried
    void catalogGrantAuthorizesDeepProduct() {
        AncestorResolver resolver = TestAncestorResolvers.ancestors(List.of(CATALOG_REF, CATEGORY_REF));
        when(supplier.lookup(eq("user-1"), eq("catalog"), eq(CATALOG)))
                .thenReturn(Optional.of(catalogViewer()));
        when(opaClient.allow(any())).thenReturn(true);

        boolean allowed = authorizer(resolver).isAllowed(subject(), "read", product());

        assertThat(allowed).isTrue();
        // U5 — role resolved on the ROOT (catalog), not the leaf
        verify(supplier).lookup("user-1", "catalog", CATALOG);
        verify(supplier, never()).lookup("user-1", "product", PRODUCT);
        // U6 — the context carries the leaf tags AND the root-first/leaf-excluded chain
        ArgumentCaptor<AbacContext> ctx = ArgumentCaptor.forClass(AbacContext.class);
        verify(opaClient).allow(ctx.capture());
        AbacContext.Resource resource = ctx.getValue().resource();
        assertThat(resource.type()).isEqualTo("product");
        assertThat(resource.id()).isEqualTo(PRODUCT);
        assertThat(resource.attributes()).containsEntry("sku", "X-1");
        assertThat(resource.ancestors()).containsExactly(CATALOG_REF, CATEGORY_REF);
        assertThat(ctx.getValue().action()).isEqualTo("product:read");
        assertThat(ctx.getValue().roleDefinition().code()).isEqualTo("catalog-viewer");
    }

    @Test // I11 — a resolver FAILURE → no ancestors → direct grant only (role on the LEAF), never wider
    void resolverFailureFallsBackToDirectGrantOnly() {
        AncestorResolver failing = TestAncestorResolvers.ancestorsThrowing(
                () -> new AncestorResolutionException("broken chain"));
        // role now resolves on the LEAF (product), since the chain collapsed to empty
        when(supplier.lookup(eq("user-1"), eq("product"), eq(PRODUCT)))
                .thenReturn(Optional.of(new RoleDefinition("p", Map.of(), Map.of("product", List.of("read")))));
        when(opaClient.allow(any())).thenReturn(true);

        boolean allowed = authorizer(failing).isAllowed(subject(), "read", product());

        assertThat(allowed).isTrue();
        verify(supplier).lookup("user-1", "product", PRODUCT); // resolved on the leaf, not a phantom root
        ArgumentCaptor<AbacContext> ctx = ArgumentCaptor.forClass(AbacContext.class);
        verify(opaClient).allow(ctx.capture());
        // NO ancestors carried — the inherited path is unreachable, so OPA can only use the direct grant
        assertThat(ctx.getValue().resource().ancestors()).isEmpty();
    }

    @Test // I11 — resolver failure + no direct grant → deny (a failed walk never widens)
    void resolverFailureWithoutDirectGrantDenies() {
        AncestorResolver failing = TestAncestorResolvers.ancestorsThrowing(
                () -> new AncestorResolutionException("broken"));
        when(supplier.lookup(eq("user-1"), eq("product"), eq(PRODUCT)))
                .thenReturn(Optional.of(new RoleDefinition("p", Map.of(), Map.of())));
        when(opaClient.allow(any())).thenReturn(false); // no direct grant in the policy

        assertThat(authorizer(failing).isAllowed(subject(), "read", product())).isFalse();
    }

    @Test // I12 — an unresolved role → deny (fail-closed), no OPA call
    void unresolvedRoleDenies() {
        AncestorResolver resolver = TestAncestorResolvers.ancestors(List.of(CATALOG_REF, CATEGORY_REF));
        when(supplier.lookup(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(authorizer(resolver).isAllowed(subject(), "read", product())).isFalse();
        verify(opaClient, never()).allow(any());
    }

    @Test // no subject → deny, no resolver/OPA call
    void noSubjectDenies() {
        AncestorResolver resolver = TestAncestorResolvers.ancestors(List.of(CATALOG_REF));
        assertThat(authorizer(resolver).isAllowed(null, "read", product())).isFalse();
        verify(opaClient, never()).allow(any());
    }

    @Test // I13 — opt-in OFF (resolver returns empty, e.g. no inheritable config) → direct-only on the leaf
    void optInOffMeansDirectOnly() {
        AncestorResolver empty = TestAncestorResolvers.ancestors(List.of()); // no ancestors surfaced
        when(supplier.lookup(eq("user-1"), eq("product"), eq(PRODUCT)))
                .thenReturn(Optional.of(new RoleDefinition("p", Map.of(), Map.of("product", List.of("read")))));
        when(opaClient.allow(any())).thenReturn(true);

        assertThat(authorizer(empty).isAllowed(subject(), "read", product())).isTrue();
        verify(supplier).lookup("user-1", "product", PRODUCT); // role on the leaf itself
        ArgumentCaptor<AbacContext> ctx = ArgumentCaptor.forClass(AbacContext.class);
        verify(opaClient).allow(ctx.capture());
        assertThat(ctx.getValue().resource().ancestors()).isEmpty();
    }

    @Test // an OPA-side error denies (fail-closed)
    void opaErrorDenies() {
        AncestorResolver resolver = TestAncestorResolvers.ancestors(List.of(CATALOG_REF));
        when(supplier.lookup(any(), any(), any())).thenReturn(Optional.of(catalogViewer()));
        when(opaClient.allow(any())).thenThrow(new RuntimeException("opa down"));

        assertThat(authorizer(resolver).isAllowed(subject(), "read", product())).isFalse();
    }
}
