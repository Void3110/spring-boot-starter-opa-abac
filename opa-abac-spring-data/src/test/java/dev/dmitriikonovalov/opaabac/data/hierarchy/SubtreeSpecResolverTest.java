package dev.dmitriikonovalov.opaabac.data.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.AbacDataObject;
import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/**
 * Unit tests for {@link SubtreeSpecResolver} (QA cases U1–U5, U5b) with a mocked {@link AncestorResolver} +
 * {@link RoleDefinitionSupplier}. Pins the <b>root-only resolution + inheritable gate</b> and that every
 * fail path lands on {@link Optional#empty()} (no widening) — never wider.
 */
class SubtreeSpecResolverTest {

    private static final String USER = "user-1";
    private static final String CATALOG_ID = "11111111-1111-1111-1111-111111111111";
    private static final ParentRef CATALOG_ROOT = new ParentRef("catalog", CATALOG_ID);

    // The inheritance declaration: a category inherits from a catalog ancestor (opt-in).
    private static final Map<String, List<String>> INHERITABLE =
            Map.of("category", List.of("catalog"), "product", List.of("category", "catalog"));

    private final AncestorResolver ancestorResolver = mock(AncestorResolver.class);
    private final RoleDefinitionSupplier supplier = mock(RoleDefinitionSupplier.class);

    private SubtreeSpecResolver resolver() {
        return new SubtreeSpecResolver(ancestorResolver, supplier, INHERITABLE);
    }

    private static AbacContext.Subject subject() {
        return new AbacContext.Subject(USER, List.of(), Map.of());
    }

    /**
     * A role that inheritably grants {@code read} to a category via the catalog. Per the Rego
     * {@code inherited_grant} clause, an inheritable grant is {@code verb in permissions[ancestor.type]} —
     * so the catalog-resolved role carries {@code read} under the <b>catalog</b> (root) type, and the
     * {@code category -> [catalog]} inheritance declaration flows it down to the listed categories.
     */
    private static RoleDefinition catalogGrantsRead() {
        return new RoleDefinition("inheriting-role", Map.of(), Map.of("catalog", List.of("read")));
    }

    @SuppressWarnings("unchecked")
    private static Specification<AbacDataObject> stubSpec() {
        return (Specification<AbacDataObject>) mock(Specification.class);
    }

    @Test // U1 — role on the governing root grants the verb AND the relation is inheritable → Optional.of
    void granted_andInheritable_widens() {
        Specification<AbacDataObject> subtree = stubSpec();
        when(supplier.lookup(USER, "catalog", CATALOG_ID))
                .thenReturn(Optional.of(catalogGrantsRead()));
        when(ancestorResolver.<AbacDataObject>subtreeOf("catalog", CATALOG_ID)).thenReturn(subtree);

        Optional<Specification<AbacDataObject>> result =
                resolver().subtreeSpec(subject(), "category", CATALOG_ROOT, "read");

        assertThat(result).containsSame(subtree);
        verify(ancestorResolver).subtreeOf("catalog", CATALOG_ID); // called once
    }

    @Test // U5b — the role is resolved on the GOVERNING ROOT (not a leaf), once
    void roleResolvedOnGoverningRoot_once() {
        when(supplier.lookup(USER, "catalog", CATALOG_ID))
                .thenReturn(Optional.of(catalogGrantsRead()));
        when(ancestorResolver.<AbacDataObject>subtreeOf(anyString(), anyString())).thenReturn(stubSpec());

        resolver().subtreeSpec(subject(), "category", CATALOG_ROOT, "read");

        verify(supplier).lookup(USER, "catalog", CATALOG_ID); // on the root
        verify(supplier, never()).lookup(eq(USER), eq("category"), anyString());
    }

    @Test // U2 — relation NOT declared inheritable (default-off) → empty, no widening, no resolver call
    void notInheritable_isEmpty() {
        // "widget" is not in the inheritance declaration at all.
        Optional<Specification<AbacDataObject>> result =
                resolver().subtreeSpec(subject(), "widget", CATALOG_ROOT, "read");

        assertThat(result).isEmpty();
        verify(ancestorResolver, never()).subtreeOf(anyString(), anyString());
        verify(supplier, never()).lookup(anyString(), anyString(), anyString());
    }

    @Test // U2 — the relation declared inheritable for OTHER ancestors but not this root's type → empty
    void inheritableButNotFromThisRootType_isEmpty() {
        // category inherits from catalog; a product-root would not be an inheritable ancestor of a category.
        ParentRef productRoot = new ParentRef("product", "p-1");
        Optional<Specification<AbacDataObject>> result =
                resolver().subtreeSpec(subject(), "category", productRoot, "read");

        assertThat(result).isEmpty();
        verify(supplier, never()).lookup(anyString(), anyString(), anyString());
    }

    @Test // U3 — no role definition resolved on the root → empty (fail-closed)
    void noRoleDefinition_isEmpty() {
        when(supplier.lookup(USER, "catalog", CATALOG_ID)).thenReturn(Optional.empty());

        Optional<Specification<AbacDataObject>> result =
                resolver().subtreeSpec(subject(), "category", CATALOG_ROOT, "read");

        assertThat(result).isEmpty();
        verify(ancestorResolver, never()).subtreeOf(anyString(), anyString());
    }

    @Test // U4 — role resolved but does NOT grant the verb on the root type → empty
    void roleLacksVerb_isEmpty() {
        // grants on the catalog root, but only WRITE — so a category:read list must not widen.
        RoleDefinition catalogWriteOnly =
                new RoleDefinition("r", Map.of(), Map.of("catalog", List.of("write")));
        when(supplier.lookup(USER, "catalog", CATALOG_ID)).thenReturn(Optional.of(catalogWriteOnly));

        Optional<Specification<AbacDataObject>> result =
                resolver().subtreeSpec(subject(), "category", CATALOG_ROOT, "read");

        assertThat(result).isEmpty();
        verify(ancestorResolver, never()).subtreeOf(anyString(), anyString());
    }

    @Test // U4 — role has no permission on the governing-root (catalog) type → empty
    void roleGrantsDifferentVerb_isEmpty() {
        // a role with NO permission on the governing-root (catalog) type at all → no inheritable grant.
        RoleDefinition noCatalogGrant =
                new RoleDefinition("r", Map.of(), Map.of("category", List.of("read")));
        when(supplier.lookup(USER, "catalog", CATALOG_ID)).thenReturn(Optional.of(noCatalogGrant));

        assertThat(resolver().subtreeSpec(subject(), "category", CATALOG_ROOT, "read")).isEmpty();
    }

    @Test // U5 — the role lookup throws → empty (fail-closed, swallowed, never propagated as widening)
    void lookupThrows_isEmpty() {
        when(supplier.lookup(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("supplier down"));

        Optional<Specification<AbacDataObject>> result =
                resolver().subtreeSpec(subject(), "category", CATALOG_ROOT, "read");

        assertThat(result).isEmpty();
        verify(ancestorResolver, never()).subtreeOf(anyString(), anyString());
    }

    @Test // a null subject → empty, no lookups
    void nullSubject_isEmpty() {
        assertThat(resolver().subtreeSpec(null, "category", CATALOG_ROOT, "read")).isEmpty();
        verify(supplier, never()).lookup(anyString(), anyString(), anyString());
    }

    @Test // a null/empty inheritance declaration → never widens (default-off)
    void emptyInheritanceDeclaration_neverWidens() {
        SubtreeSpecResolver off = new SubtreeSpecResolver(ancestorResolver, supplier, Map.of());
        assertThat(off.subtreeSpec(subject(), "category", CATALOG_ROOT, "read")).isEmpty();
        verify(supplier, never()).lookup(anyString(), anyString(), anyString());
    }

    @Test // even a granted+inheritable resolution that throws inside subtreeOf is safe — Optional present but
    // subtreeOf is itself fail-closed (empty predicate). Here we just confirm the resolver propagates the spec.
    void granted_propagatesResolverSpec() {
        Specification<AbacDataObject> subtree = stubSpec();
        when(supplier.lookup(USER, "catalog", CATALOG_ID))
                .thenReturn(Optional.of(catalogGrantsRead()));
        when(ancestorResolver.<AbacDataObject>subtreeOf(any(), any())).thenReturn(subtree);
        assertThat(resolver().subtreeSpec(subject(), "category", CATALOG_ROOT, "read")).containsSame(subtree);
    }
}
