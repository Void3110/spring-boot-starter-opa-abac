package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import dev.dmitriikonovalov.opaabac.data.hierarchy.HierarchicalPathMaintainer;
import dev.dmitriikonovalov.opaabac.data.hierarchy.LtreePathSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the example's hierarchy data-access seam (Phase 5.5-A). The library owns the resolver/maintainer
 * algorithms; the app supplies how to read a resource's {@code ltree path} from its table — the seam the
 * library can't know.
 *
 * <p>The {@link LtreePathSource} bean here makes the starter's {@code HierarchyAutoConfiguration} wire the
 * default {@code LtreeAncestorResolver} (enabled via {@code opa.abac.hierarchy.enabled=true} +
 * {@code resolver=ltree}). The {@link HierarchicalPathMaintainer} is exposed for the app's create/re-parent
 * flows to assign + rewrite paths.
 */
@Configuration
public class HierarchyConfig {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Reads the materialized {@code ltree path} for a resource {@code (type, id)} from the matching table.
     * The three hierarchy tables (catalog/category/product) each carry a {@code path} column.
     */
    @Bean
    public LtreePathSource ltreePathSource() {
        return (type, id) -> {
            String table = tableFor(type);
            if (table == null) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            java.util.List<Object> rows = entityManager
                    .createNativeQuery("SELECT path::text FROM " + table + " WHERE id = CAST(? AS uuid)")
                    .setParameter(1, id)
                    .getResultList();
            return rows.isEmpty() ? Optional.empty() : Optional.ofNullable((String) rows.get(0));
        };
    }

    @Bean
    public HierarchicalPathMaintainer hierarchicalPathMaintainer(LtreePathSource ltreePathSource) {
        return new HierarchicalPathMaintainer(entityManager, ltreePathSource);
    }

    /** Map an ABAC resource type to its physical table (the only place the type↔table mapping lives). */
    static String tableFor(String type) {
        return switch (type) {
            case "catalog" -> "catalog";
            case "category" -> "category";
            case "product" -> "product";
            default -> null;
        };
    }

    /** Map an ABAC resource type to its physical table, for a {@link ParentRef}. */
    static String tableFor(ParentRef ref) {
        return tableFor(ref.type());
    }
}
