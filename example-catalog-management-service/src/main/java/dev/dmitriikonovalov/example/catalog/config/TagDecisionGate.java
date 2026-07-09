package dev.dmitriikonovalov.example.catalog.config;

import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The delta-aware gate dispatch for the TAG/WRITE boundary (Phase 6.5, pinned semantic #2): a static
 * {@code @OpaPreAuthorize(<type>:update)} cannot express "TAG-without-WRITE relabels but never edits",
 * so the category <b>update</b> handler dispatches conditionally on what the request actually changes —
 * content delta → the {@code update} decision; tags delta → the {@code assign-tags} decision; both →
 * both; an empty delta → {@code update} (the conservative default — a no-op PUT by a TAG-only holder
 * answers 403, fail-closed). Create keeps its static {@code create} annotation plus a conditional
 * <b>type-level</b> {@code assign-tags} decision when the request carries tags (no instance exists yet
 * — tag-on-create semantics).
 *
 * <p>Each method carries its own {@code @OpaPreAuthorize}, so every decision still flows through the
 * <b>manager seam</b>: the resolver loads the instance's real attributes/ancestors and the 5.97
 * write-through request cache makes the second decision's resolution free — <b>zero library change</b>.
 * Spring AOP requires the calls to cross a bean boundary, hence this dedicated bean (never
 * self-invocation from a controller method).
 *
 * <p>Category and catalog: the two types whose REST requests carry {@code tags}. Product requests
 * have no tags field, so their tags delta is never constructible and their PUT keeps a static
 * {@code product:update} annotation (a dispatch there would have an unreachable branch). Catalog
 * create takes NO tags (rejected 422 before any decision): the type-level assign-tags decision
 * resolves through the governing team, and a new catalog has no team until owner-on-create binds
 * one — so catalog tag assignment starts at the first update.
 */
@Component
public class TagDecisionGate {

    /** The content-change decision on a resolved catalog instance. */
    @OpaPreAuthorize(action = "catalog:update", resourceType = "'catalog'", resourceId = "#catalogId")
    public void requireCatalogUpdate(UUID catalogId) {
        // The decision IS the method — the @OpaPreAuthorize interceptor throws on deny.
    }

    /** The tag-relabel decision on a resolved catalog instance. */
    @OpaPreAuthorize(action = "catalog:assign-tags", resourceType = "'catalog'", resourceId = "#catalogId")
    public void requireCatalogAssignTags(UUID catalogId) {
        // The decision IS the method — the @OpaPreAuthorize interceptor throws on deny.
    }

    /** The content-change decision on a resolved category instance. */
    @OpaPreAuthorize(action = "category:update", resourceType = "'category'", resourceId = "#categoryId")
    public void requireCategoryUpdate(UUID categoryId) {
        // The decision IS the method — the @OpaPreAuthorize interceptor throws on deny.
    }

    /** The tag-relabel decision on a resolved category instance. */
    @OpaPreAuthorize(action = "category:assign-tags", resourceType = "'category'", resourceId = "#categoryId")
    public void requireCategoryAssignTags(UUID categoryId) {
        // The decision IS the method — the @OpaPreAuthorize interceptor throws on deny.
    }

    /**
     * The TYPE-LEVEL tag decision for create — no instance exists yet. Slice B4: the role is resolved on
     * the parent {@code catalog} (the governing root) so a member with {@code assign-tags} (TAG) on the
     * catalog passes; a non-member resolves no role and is denied.
     */
    @OpaPreAuthorize(action = "category:assign-tags", resourceType = "'category'",
            roleResourceType = "'catalog'", roleResourceId = "#catalogId")
    public void requireCategoryAssignTagsForCreate(UUID catalogId) {
        // The decision IS the method — the @OpaPreAuthorize interceptor throws on deny.
    }
}
