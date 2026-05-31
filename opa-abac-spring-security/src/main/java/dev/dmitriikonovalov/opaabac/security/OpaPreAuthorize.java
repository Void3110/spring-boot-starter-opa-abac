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
 * name an {@link dev.dmitriikonovalov.opaabac.core.AbacDataObject} directly. Per-instance, attribute-based
 * checks on a loaded entity are a later phase.
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
}
