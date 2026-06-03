package dev.dmitriikonovalov.example.catalog.web;

import dev.dmitriikonovalov.example.catalog.domain.CatalogEntity;
import dev.dmitriikonovalov.example.catalog.domain.CategoryEntity;
import dev.dmitriikonovalov.example.catalog.domain.ProductEntity;
import dev.dmitriikonovalov.example.catalog.openapi.model.Catalog;
import dev.dmitriikonovalov.example.catalog.openapi.model.Category;
import dev.dmitriikonovalov.example.catalog.openapi.model.Product;

/** Maps JPA entities to the generated OpenAPI DTOs. */
public final class CatalogMapper {

    private CatalogMapper() {
    }

    public static Catalog toDto(CatalogEntity e) {
        return new Catalog()
                .id(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .createdAt(e.getCreatedAt());
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
                .currency(e.getCurrency());
    }
}
