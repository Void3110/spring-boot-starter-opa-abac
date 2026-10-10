package dev.dmitriikonovalov.opaabac.mcp.authz;

import java.util.Map;

/**
 * The tool-gate's <strong>input contract</strong> version (ADR 0036 §7): every tool-gate input — each
 * single decision and every item of a roster batch — carries
 * {@code input.environment.schema = }{@value #SCHEMA_V1}.
 *
 * <p>A v1-aware policy (the shipped {@code agent_tools.rego}) grants nothing for a version it does not
 * know, so a jar and a policy that disagree on the input layout <strong>deny</strong> instead of misreading
 * a moved field. v1 is the layout documented in the agent tool-authorization guide; in particular the
 * actor stays at {@code input.subject.attributes.actor}. Any incompatible change to the input ships as a new
 * version, never under this one.
 *
 * <p>A policy written before v1 ignores the marker — the guarantee starts with v1-aware policies; deploy
 * the v1-aware policy with the jar that sends v1.
 */
public final class ToolGateInput {

    /** The key under {@code input.environment} that carries the version. */
    public static final String SCHEMA_KEY = "schema";

    /** Version 1 of the tool-gate input. */
    public static final String SCHEMA_V1 = "opa-abac.mcp.tool-gate/v1";

    private ToolGateInput() {
    }

    /** The {@code environment} every tool-gate input carries: the version marker, and nothing else. */
    public static Map<String, Object> environment() {
        return Map.of(SCHEMA_KEY, SCHEMA_V1);
    }
}
