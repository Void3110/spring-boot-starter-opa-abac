package dev.dmitriikonovalov.example.mcp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.modelcontextprotocol.spec.McpError;
import org.springframework.boot.jackson.JacksonMixin;

/**
 * Keeps the MCP transport's own error responses to the error itself.
 *
 * <p>Spring AI 2.0.1's streamable transport answers its refusals — an {@code Accept} header without
 * {@code text/event-stream} ({@code 400}), an unknown session ({@code 404}) — by handing the {@link McpError}
 * itself to the HTTP message converters as the response body. An exception serialized as a bean carries every
 * property it has: a full {@code stackTrace} (117 frames on the rig, framework classes and this application's),
 * the {@code cause} chain and {@code suppressed}, about 29 KB sent to any caller with a token. This mix-in,
 * registered by Boot's {@link JacksonMixin} scan, leaves the JSON-RPC error and its message.
 *
 * <p>It applies to {@code McpError} only, and only where Boot's JSON mapper writes it — the MVC converters. The
 * MCP SDK's own JSON-RPC serialization is unaffected.
 */
@JacksonMixin(McpError.class)
@JsonIgnoreProperties({"stackTrace", "cause", "suppressed", "localizedMessage"})
abstract class McpErrorJsonMixin {
}
