package dev.dmitriikonovalov.example.mcp.authz;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.lang.reflect.Field;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Installs {@link ToolRosterFilter} into the MCP SDK's live request-handler map, by wrapping the
 * {@code "tools/list"} entry with delegate-then-filter.
 *
 * <h2>This class is DISPOSABLE — delete it when java-sdk #578 ships</h2>
 * MCP Java SDK 2.0.0 has <strong>no supported seam</strong> for varying {@code tools/list} per caller.
 * The handler is a private lambda registered into a plain {@code HashMap} by {@code McpAsyncServer}, held
 * in a package-private field on the session factory; it reads the live global tool list and ignores both
 * of its arguments, including the per-request exchange. Every Spring AI customizer runs at construction
 * time only. The supported seam is java-sdk <strong>#578</strong> (a pluggable {@code ToolsRepository}
 * whose list method receives per-request context), targeted at the opt-in 2.1 minor — unreleased.
 *
 * <p>So: two reflective field reads, workable because the jars are non-modular, isolated in this one
 * class. Everything that decides anything lives in {@link ToolRosterFilter} and survives the migration.
 * When #578 lands, this file is deleted and the filter is registered through the supported API; nothing
 * else moves.
 *
 * <h2>Pinned internals (true for SDK 2.0.0; expected false at 2.1)</h2>
 * <ol>
 *   <li>{@code McpStreamableServerTransportProvider} implementation → private field {@code sessionFactory}</li>
 *   <li>that factory (a {@code DefaultMcpStreamableServerSessionFactory}) → field {@code requestHandlers}</li>
 *   <li>that map holds an entry under {@link McpSchema#METHOD_TOOLS_LIST}</li>
 * </ol>
 *
 * <p>The provider is resolved <strong>by interface type</strong>, never by bean name or concrete class,
 * so a test can substitute a stub — which is how the smoke check's failure branch is exercised.
 *
 * <h2>Installation failure fails startup — never a silent no-op</h2>
 * If an upgrade moves any pin, the context fails with an error naming what was not found. Degrading to an
 * unfiltered roster here would be <em>safe</em> — the roster is only a hint — but it would switch the
 * slice's flagship feature off silently, and a demo whose headline can quietly vanish is worse than one
 * that refuses to boot after an unverified upgrade. The deliberate escape hatch is
 * {@code example.mcp.authz.roster-filter.enabled=false}, which skips this bean entirely, smoke check and
 * all.
 *
 * <p>One wrap covers every caller: all sessions share the one map. It is written exactly once, here,
 * before any traffic — never mutated afterwards, and never per session. (Shaping the roster with
 * {@code addTool}/{@code removeTool} instead would mutate global state, leak across sessions, race, and
 * is forbidden by the 2026-07-28 spec revision.)
 */
public class RosterFilterInstaller implements SmartInitializingSingleton {

    static final String SESSION_FACTORY_FIELD = "sessionFactory";
    static final String REQUEST_HANDLERS_FIELD = "requestHandlers";

    private static final Logger log = LoggerFactory.getLogger(RosterFilterInstaller.class);

    private static final String PIN_HINT = "The roster adapter reflects into MCP SDK internals pinned at "
            + "2.0.0 (transport provider -> '" + SESSION_FACTORY_FIELD + "' -> '" + REQUEST_HANDLERS_FIELD
            + "' -> the \"" + McpSchema.METHOD_TOOLS_LIST + "\" entry). An SDK or Spring AI upgrade has "
            + "moved them. Port RosterFilterInstaller (see java-sdk #578, which replaces it outright), or "
            + "set example.mcp.authz.roster-filter.enabled=false to boot with an unfiltered roster — "
            + "call-time enforcement is unaffected either way.";

    private final ObjectProvider<McpStreamableServerTransportProvider> transportProviders;
    private final ToolRosterFilter filter;

    public RosterFilterInstaller(
            ObjectProvider<McpStreamableServerTransportProvider> transportProviders,
            ToolRosterFilter filter) {
        this.transportProviders = transportProviders;
        this.filter = filter;
    }

    @Override
    public void afterSingletonsInstantiated() {
        McpStreamableServerTransportProvider provider = transportProviders.getIfAvailable();
        if (provider == null) {
            // Almost always the protocol pin: with spring.ai.mcp.server.protocol absent, the 2.0.0
            // auto-config conditionals match "SSE" by default and the streamable provider is never
            // created — POST /mcp 404s and this seam does not exist. Naming it here turns a confusing
            // routing failure into a one-line fix.
            throw new IllegalStateException(
                    "No McpStreamableServerTransportProvider bean: the server is not running the "
                            + "streamable transport. Set spring.ai.mcp.server.protocol=STREAMABLE "
                            + "explicitly — the auto-configuration matches the property string and "
                            + "ignores the properties-field default, so an absent property serves the "
                            + "legacy SSE transport (GET /sse + POST /mcp/message).");
        }

        Map<String, McpRequestHandler<?>> handlers = requestHandlers(provider);
        McpRequestHandler<?> original = handlers.get(McpSchema.METHOD_TOOLS_LIST);
        if (original == null) {
            throw new IllegalStateException("No \"" + McpSchema.METHOD_TOOLS_LIST
                    + "\" entry in the session factory's '" + REQUEST_HANDLERS_FIELD + "' map (found: "
                    + handlers.keySet() + "). " + PIN_HINT);
        }

        handlers.put(McpSchema.METHOD_TOOLS_LIST,
                new RosterFilteringHandler(asListHandler(original), filter));
        log.info("Roster filter installed on \"{}\" (delegate-then-filter; {} handler(s) in the map)",
                McpSchema.METHOD_TOOLS_LIST, handlers.size());
    }

    /**
     * The installed handler: delegate to the original, then filter its result.
     *
     * <p>A <strong>named class rather than a lambda</strong>, so a test can assert against the real
     * application context that the map's {@code "tools/list"} entry <em>is</em> this — the same invariant
     * {@code ToolGateInstallationTest} pins for the call path. Everything else about the roster could be
     * correct and still never run if the entry the server invokes were the raw handler.
     */
    static final class RosterFilteringHandler implements McpRequestHandler<ListToolsResult> {

        private final McpRequestHandler<ListToolsResult> delegate;
        private final ToolRosterFilter filter;

        RosterFilteringHandler(McpRequestHandler<ListToolsResult> delegate, ToolRosterFilter filter) {
            this.delegate = delegate;
            this.filter = filter;
        }

        /**
         * Decide eagerly on the caller's thread; apply lazily to the delegate's result.
         *
         * <p>The split is the point. {@code map}'s lambda runs at subscription time, and the identity the
         * filter needs lives in thread-locals that are only provably in scope while this method is
         * executing. Resolving the decision first — then applying a plain value — means the feature does
         * not rest on an unwritten scheduling guarantee.
         */
        @Override
        public reactor.core.publisher.Mono<ListToolsResult> handle(
                McpAsyncServerExchange exchange, Object params) {
            RosterDecision decision = decideSafely();
            return delegate.handle(exchange, params)
                    .map(result -> ToolRosterFilter.apply(decision, result));
        }

        /**
         * A decision, or the unfiltered fallback if the filter itself failed unexpectedly.
         *
         * <p>{@link ToolRosterFilter#decide()} already lands every anticipated failure on a decision, so
         * this only catches the unanticipated. It is deliberately the <em>runtime</em> posture — degrade
         * the hint — rather than the startup one: refusing to answer {@code tools/list} at all would take
         * the server down over a filter bug, while the authoritative gate keeps enforcing regardless.
         */
        private RosterDecision decideSafely() {
            try {
                return filter.decide();
            } catch (RuntimeException e) {
                log.warn("Roster unfiltered: the roster pre-flight failed unexpectedly. "
                        + "Call-time enforcement is unaffected.", e);
                return RosterDecision.unfiltered();
            }
        }
    }

    private Map<String, McpRequestHandler<?>> requestHandlers(
            McpStreamableServerTransportProvider provider) {
        Object sessionFactory = read(provider, SESSION_FACTORY_FIELD);
        Object handlers = read(sessionFactory, REQUEST_HANDLERS_FIELD);
        // `read` never returns null — it throws on an absent or null-valued field — so the only
        // remaining possibility is a field that is present but no longer a Map.
        if (!(handlers instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Field '" + REQUEST_HANDLERS_FIELD + "' on "
                    + sessionFactory.getClass().getName() + " is not a Map (was "
                    + handlers.getClass().getName() + "). " + PIN_HINT);
        }
        @SuppressWarnings("unchecked")
        Map<String, McpRequestHandler<?>> typed = (Map<String, McpRequestHandler<?>>) map;
        return typed;
    }

    /**
     * Read one pinned field off {@code target}, walking up the hierarchy so a field that moves to a
     * superclass in a future release still resolves. Never returns null: an absent field, a null value
     * and an unreadable field all throw with the pins named.
     */
    private static Object read(Object target, String fieldName) {
        // Resolved once, up front: the loop variable below is null by the time it exits, so every
        // message must be built from the type we started at, not from wherever the walk ended.
        String typeName = target.getClass().getName();
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(target);
                if (value == null) {
                    throw new IllegalStateException("Field '" + fieldName + "' on " + typeName
                            + " is null. " + PIN_HINT);
                }
                return value;
            } catch (NoSuchFieldException _) {
                // Keep walking; the pin may have moved to a superclass rather than disappeared.
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Field '" + fieldName + "' on " + typeName
                        + " could not be read. " + PIN_HINT, e);
            }
        }
        throw new IllegalStateException(
                "No field '" + fieldName + "' on " + typeName + " or any supertype. " + PIN_HINT);
    }

    @SuppressWarnings("unchecked")
    private static McpRequestHandler<ListToolsResult> asListHandler(McpRequestHandler<?> handler) {
        return (McpRequestHandler<ListToolsResult>) handler;
    }
}
