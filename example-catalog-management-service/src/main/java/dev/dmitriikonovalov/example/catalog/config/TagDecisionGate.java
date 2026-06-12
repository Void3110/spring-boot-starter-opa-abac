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
 * <p>Category only: it is the one type whose REST requests carry {@code tags} — catalog and product
 * requests have no tags field, so their tags delta is never constructible and their PUTs keep a static
 * {@code <type>:update} annotation (a dispatch there would have an unreachable branch).
 */
@Component
public class TagDecisionGate {

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

    /** The TYPE-LEVEL tag decision for create — no instance exists yet. */
    @OpaPreAuthorize(action = "category:assign-tags", resourceType = "'category'")
    public void requireCategoryAssignTagsForCreate() {
        // The decision IS the method — the @OpaPreAuthorize interceptor throws on deny.
    }
}
