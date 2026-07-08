package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.example.catalog.config.CategoryListAuthorizer;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryRepository;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.data.filter.AbacQueryService;
import dev.dmitriikonovalov.opaabac.data.hierarchy.SubtreeSpecResolver;
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
 * B2 (QA U13): {@link CategoryListAuthorizer} maps a role-source outage to a fail-closed empty page,
 * never letting the {@link RoleResolutionException} escape as a 500 and never calling the query service
 * (so the outage never reaches the filter / OPA).
 */
class CategoryListAuthorizerOutageTest {

    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final RoleDefinitionSupplier supplier = mock(RoleDefinitionSupplier.class);
    private final AbacQueryService queryService = mock(AbacQueryService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AbacQueryService> queryServiceProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SubtreeSpecResolver> subtreeProvider = mock(ObjectProvider.class);

    private final CategoryListAuthorizer authorizer =
            new CategoryListAuthorizer(categories, supplier, queryServiceProvider, subtreeProvider);

    private final Pageable pageable = PageRequest.of(0, 20, Sort.by("createdAt").ascending().and(Sort.by("id")));

    @BeforeEach
    void authenticate() {
        when(queryServiceProvider.getIfAvailable()).thenReturn(queryService);
        AbacContext.Subject subject =
                new AbacContext.Subject("user-1", List.of("catalog-editor"), Map.of());
        SecurityContextHolder.getContext().setAuthentication(new AbacAuthentication(subject));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test // U13 — the role lookup throws (outage) → empty page, the query service is never called
    void roleSourceOutage_returnsEmptyPage_neverQueries() {
        when(supplier.lookup(any(), any(), any()))
                .thenThrow(new RoleResolutionException("source unavailable"));

        Page<CategoryEntity> page = authorizer.readable(UUID.randomUUID(), null, pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(queryService, never()).findAuthorized(any(), any(), any(), any(), any());
    }
}
