package dev.dmitriikonovalov.opaabac.core;

/**
 * Client for evaluating ABAC decisions against an OPA server.
 *
 * <p>Phase 1 will provide a zero-dependency JDK {@code HttpClient} implementation;
 * later phases add batch evaluation and partial evaluation.
 */
public interface OpaClient {

    /**
     * Evaluate a single authorization decision.
     *
     * @param context the ABAC context (serialized as OPA {@code input})
     * @return {@code true} if the policy allows the action
     */
    boolean allow(AbacContext context);
}
