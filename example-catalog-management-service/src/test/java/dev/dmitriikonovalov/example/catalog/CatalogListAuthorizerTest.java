package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.example.catalog.config.CatalogListAuthorizer;
import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CatalogRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.data.filter.AbacQueryService;
import dev.dmitriikonovalov.opaabac.data.filter.GovernedScopeResolver;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Fail-closed unit tests for {@link CatalogListAuthorizer} (Slice B4, QA I2/I4 + the outage/no-resolver
 * edges), mirroring {@code CategoryListAuthorizerOutageTest}. Each asserts the empty-page floor and that
 * the breach never reaches the query service (so no leak and no 500). The different-row-sets cut (I1/I3/I5)
 * is the {@code CatalogListIsolationIT} against real Postgres; this pins the fail-closed branches in
 * isolation with mocks.
 */
class CatalogListAuthorizerTest {

    private final CatalogRepository catalogs = mock(CatalogRepository.class);
    private final RoleDefinitionSupplier supplier = mock(RoleDefinitionSupplier.class);
    private final AbacQueryService queryService = mock(AbacQueryService.class);
    private final GovernedScopeResolver governedScopeResolver = mock(GovernedScopeResolver.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<GovernedScopeResolver> resolverProvider = mock(ObjectProvider.class);

    private final CatalogListAuthorizer authorizer =
            new CatalogListAuthorizer(catalogs, supplier, queryService, resolverProvider);

    private final Pageable pageable =
            PageRequest.of(0, 20, Sort.by("createdAt").ascending().and(Sort.by("id")));

    @BeforeEach
    void authenticate() {
        AbacContext.Subject subject =
                new AbacContext.Subject("sub-1", List.of("catalog-editor"), Map.of());
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(subject));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test // I4 — no GovernedScopeResolver bean (e.g. demo profile) → empty page, query service untouched
    void noResolverBean_returnsEmptyPage_neverQueries() {
        when(resolverProvider.getIfAvailable()).thenReturn(null);

        Page<CatalogEntity> page = authorizer.readable(pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
    }

    @Test // I2 — subject governs NOTHING (empty governed ids) → empty page, never queries
    void governsNothing_returnsEmptyPage_neverQueries() {
        when(resolverProvider.getIfAvailable()).thenReturn(governedScopeResolver);
        when(governedScopeResolver.governedIds("sub-1", "catalog")).thenReturn(List.of());

        Page<CatalogEntity> page = authorizer.readable(pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
        verify(supplier, never()).lookup(any(), any(), any()); // never even resolves a role
    }

    @Test // outage — the residual-role lookup throws → empty page, never queries (fail-closed, no 500)
    void roleSourceOutage_returnsEmptyPage_neverQueries() {
        UUID governed = UUID.randomUUID();
        when(resolverProvider.getIfAvailable()).thenReturn(governedScopeResolver);
        when(governedScopeResolver.governedIds("sub-1", "catalog")).thenReturn(List.of(governed));
        when(supplier.lookup(eq("sub-1"), eq("catalog"), eq(governed.toString())))
                .thenThrow(new RoleResolutionException("source unavailable"));

        Page<CatalogEntity> page = authorizer.readable(pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
    }

    @Test // unauthenticated → empty page, nothing touched
    void unauthenticated_returnsEmptyPage() {
        SecurityContextHolder.clearContext();

        Page<CatalogEntity> page = authorizer.readable(pageable);

        assertThat(page.getContent()).isEmpty();
        verify(resolverProvider, never()).getIfAvailable();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
    }

    @Test // happy path — governs catalogs, role resolves → delegates to findAuthorized with the governed
    // scope as the base scope and a null subtreeSpec (catalogs are roots). The actual row cut is the IT.
    void governs_resolvesRoleOnFirstGoverned_andDelegates() {
        UUID g1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID g2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        RoleDefinition role =
                new RoleDefinition("catalog-editor", Map.of(), Map.of("catalog", List.of("READ", "WRITE")));
        when(resolverProvider.getIfAvailable()).thenReturn(governedScopeResolver);
        when(governedScopeResolver.governedIds("sub-1", "catalog")).thenReturn(List.of(g1, g2));
        when(supplier.lookup("sub-1", "catalog", g1.toString())).thenReturn(java.util.Optional.of(role));
        when(queryService.<CatalogEntity>findAuthorized(any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(pageable));

        authorizer.readable(pageable);

        // The role is resolved on the FIRST governed id only (g1), not g2.
        verify(supplier).lookup("sub-1", "catalog", g1.toString());
        verify(supplier, never()).lookup("sub-1", "catalog", g2.toString());
        // Delegates with a null subtreeSpec (roots) and the catalog:list context.
        verify(queryService).findAuthorized(
                eq(catalogs),
                any(),
                org.mockito.ArgumentMatchers.argThat(ctx -> ctx.action().equals("catalog:list")
                        && ctx.resource().type().equals("catalog")),
                org.mockito.ArgumentMatchers.isNull(),
                eq(pageable));
    }
}
