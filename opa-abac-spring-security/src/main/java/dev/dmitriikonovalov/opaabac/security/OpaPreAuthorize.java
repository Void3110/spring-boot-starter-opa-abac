package dev.dmitriikonovalov.opaabac.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a method requires an OPA-backed ABAC decision <em>before</em> it runs.
 *
 * <p>The {@link OpaPreAuthorizeAuthorizationManager} builds an
 * {@link dev.dmitriikonovalov.opaabac.core.AbacContext} from the current subject, the {@link #action()},
 * the resolved resource, and the looked-up role definition, then asks OPA. A deny surfaces as Spring
 * Security's {@code AccessDeniedException} (→ 403). The decision fails closed.
 *
 * <p><strong>Pre-invocation, so the resource is named by type (+ optional id), not by instance.</strong>
 * An annotation evaluated before the method runs cannot see a return value or a not-yet-loaded entity,
 * so {@link #resourceType()} / {@link #resourceId()} name the resource coarsely while the <em>decision</em>
 * is rich (role-definition driven). For callers that already hold the instance, {@link #resource()} may
 * name an {@link dev.dmitriikonovalov.opaabac.core.AbacDataObject} directly. With an
 * {@link dev.dmitriikonovalov.opaabac.core.AbacResourceResolver} registered (opt-in), a declared
 * {@link #resourceId()} is <em>resolved</em>: the gate decides on the instance's real attributes and
 * ancestor chain, the role looked up once on the governing root — see the attribute-rich
 * pre-authorization guide. A missing instance then denies at the gate (403, never the handler's 404).
 *
 * <p>{@code resourceType}, {@code resourceId}, and {@code resource} are SpEL expressions evaluated
 * against the method arguments (e.g. {@code resourceType = "'product'"}, {@code resourceId = "#productId"}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OpaPreAuthorize {

    /** The action being attempted, opaque to the library (e.g. {@code "product:write"}). */
    String action();

    /** SpEL → the resource type (e.g. {@code "'product'"}). Optional; blank means type unknown. */
    String resourceType() default "";

    /** SpEL → the resource id (e.g. {@code "#productId"}). Optional; blank means no id (type-level). */
    String resourceId() default "";

    /**
     * SpEL → an {@link dev.dmitriikonovalov.opaabac.core.AbacDataObject} instance, for callers that hold
     * one. When set and non-null it supplies the resource type/id/attributes, overriding
     * {@link #resourceType()} / {@link #resourceId()}.
     */
    String resource() default "";

    /**
     * SpEL → the type of the resource the <strong>role</strong> is resolved on, when that differs from the
     * resource being decided. Blank (the default) means the role is resolved on the decided resource itself
     * (today's behavior). Set this together with {@link #roleResourceId()} to resolve the caller's role on a
     * <strong>governing parent</strong> for a <em>type-level</em> gate that has no instance to walk up from —
     * e.g. a child <em>create</em> or <em>list</em>: the policy queried stays {@link #resourceType()} (so a
     * {@code category} create asks {@code data.category.allow}), but the role is looked up on the parent
     * {@code catalog} so the policy's inheritable-ancestor grant can fire. The decided resource (its type,
     * the policy document) is unchanged; only the role-lookup coordinates move to the governing root.
     */
    String roleResourceType() default "";

    /**
     * SpEL → the id of the resource the role is resolved on (the governing parent), paired with
     * {@link #roleResourceType()} (e.g. {@code "#catalogId"}). Blank → no override. When both are set and
     * resolve to non-blank, the role lookup uses {@code (roleResourceType, roleResourceId)} instead of the
     * decided resource's coordinates. A declared-but-unresolvable override (blank at runtime) denies
     * (fail-closed), exactly like an unresolvable {@link #resourceId()} — never a silent widening to the
     * type-level lookup.
     */
    String roleResourceId() default "";
}
