package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.ProductEntity;
import dev.dmitriikonovalov.example.catalog.openapi.model.Catalog;
import dev.dmitriikonovalov.example.catalog.openapi.model.CatalogPage;
import dev.dmitriikonovalov.example.catalog.openapi.model.Category;
import dev.dmitriikonovalov.example.catalog.openapi.model.CategoryPage;
import dev.dmitriikonovalov.example.catalog.openapi.model.Product;
import dev.dmitriikonovalov.example.catalog.openapi.model.ProductPage;
import org.springframework.data.domain.Page;

/** Maps JPA entities to the generated OpenAPI DTOs. */
public final class CatalogMapper {

    private CatalogMapper() {
    }

    public static Catalog toDto(CatalogEntity e) {
        return new Catalog()
                .id(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .createdAt(e.getCreatedAt())
                .tags(e.getTags().asMap());
    }

    public static Category toDto(CategoryEntity e) {
        return new Category()
                .id(e.getId())
                .catalogId(e.getCatalogId())
                .parentId(e.getParentId())
                .name(e.getName())
                .description(e.getDescription())
                .tags(e.getTags().asMap());
    }

    public static Product toDto(ProductEntity e) {
        return new Product()
                .id(e.getId())
                .categoryId(e.getCategoryId())
                .name(e.getName())
                .description(e.getDescription())
                .sku(e.getSku())
                .priceCents(e.getPriceCents())
                .currency(e.getCurrency())
                .tags(e.getTags().asMap());
    }

    // --- the list envelope (ADR 0012): count = the page's totalElements (the subject's authorized
    // total on the categories list; the plain query total elsewhere); page/perPage echo the request.

    public static CatalogPage toCatalogPage(Page<CatalogEntity> page) {
        return new CatalogPage()
                .count(page.getTotalElements())
                .page(page.getNumber())
                .perPage(page.getSize())
                .items(page.getContent().stream().map(CatalogMapper::toDto).toList());
    }

    public static CategoryPage toCategoryPage(Page<CategoryEntity> page) {
        return new CategoryPage()
                .count(page.getTotalElements())
                .page(page.getNumber())
                .perPage(page.getSize())
                .items(page.getContent().stream().map(CatalogMapper::toDto).toList());
    }

    public static ProductPage toProductPage(Page<ProductEntity> page) {
        return new ProductPage()
                .count(page.getTotalElements())
                .page(page.getNumber())
                .perPage(page.getSize())
                .items(page.getContent().stream().map(CatalogMapper::toDto).toList());
    }
}
