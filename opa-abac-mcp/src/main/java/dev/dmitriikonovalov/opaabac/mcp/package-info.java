/**
 * Agent tool-call authorization for MCP servers — the tool-gate (ADR 0028, packaged by ADR 0036).
 *
 * <p>Every tool call is decided by OPA as <em>principal ceiling ∩ agent capability</em>, in Rego, before
 * the tool body runs, in front of the target service's unchanged per-resource policies. This module holds
 * the types and the logic; the starter's auto-configuration builds the beans when the module sits next to
 * Spring AI's MCP server. The module itself declares no beans.
 *
 * <h2>Incubating</h2>
 * The API of this module and its subpackages is <strong>incubating</strong>: it may change in a minor
 * release until an external consumer adopts it (ADR 0036 §1). Pin the version you build against.
 *
 * <ul>
 *   <li>{@code …mcp.identity} — who is acting for whom (the delegation chain) and what an agent may do
 *       (the capability seam, with a YAML default);</li>
 *   <li>{@code …mcp.tool} — the static tool declarations and the structured tool-failure carrier.</li>
 * </ul>
 */
package dev.dmitriikonovalov.opaabac.mcp;
