/**
 * The tool-gate's decision: the PEP ({@link dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallAuthorizer}),
 * its MCP adapters — one per SDK tool-specification type, each deciding when its handler is invoked
 * ({@link dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallGate},
 * {@link dev.dmitriikonovalov.opaabac.mcp.authz.AsyncToolCallGate},
 * {@link dev.dmitriikonovalov.opaabac.mcp.authz.StatelessToolCallGate},
 * {@link dev.dmitriikonovalov.opaabac.mcp.authz.StatelessAsyncToolCallGate}), the {@code tools/list} roster decision
 * ({@link dev.dmitriikonovalov.opaabac.mcp.authz.ToolRosterFilter}) and the principal-ceiling SPI
 * ({@link dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier}). Incubating (see the parent
 * package).
 */
package dev.dmitriikonovalov.opaabac.mcp.authz;
