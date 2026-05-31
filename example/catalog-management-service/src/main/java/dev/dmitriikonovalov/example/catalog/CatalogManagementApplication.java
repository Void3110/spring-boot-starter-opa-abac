package dev.dmitriikonovalov.example.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Catalog Management Service — the example application we will secure with OPA ABAC.
 *
 * <p>Phase 0: plain CRUD over a Catalog → Category → Product hierarchy, no authentication.
 */
@SpringBootApplication
public class CatalogManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogManagementApplication.class, args);
    }
}
