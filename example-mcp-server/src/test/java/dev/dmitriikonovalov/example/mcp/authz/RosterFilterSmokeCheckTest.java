package dev.dmitriikonovalov.example.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.example.mcp.tool.ToolRegistry;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * I24: the adapter's smoke check <strong>fails the context</strong> when the pinned SDK internals are not
 * where it expects them, naming what it could not find.
 *
 * <p>This is the case that decides what happens on the day someone bumps Spring AI or the MCP SDK.
 * Degrading to an unfiltered roster would be <em>safe</em> — the roster is only a hint and the gate keeps
 * enforcing — but it would switch the slice's headline feature off silently, and the server would go on
 * cheerfully advertising every tool to every agent. Refusing to boot is the louder, more honest failure,
 * and {@code roster-filter.enabled=false} is the deliberate way to override it.
 *
 * <p>Asserted with {@code .getFailure()} rather than {@code .rootCause()}: a
 * {@code SmartInitializingSingleton} throws un-wrapped, the same idiom {@code ToolRegistryValidatorTest}
 * uses for the registry validator.
 */
class RosterFilterSmokeCheckTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FilterFixture.class);

    @Test // I24 — the provider has no 'sessionFactory' field at all: an upgrade moved the first pin
    void failsStartupWhenTheSessionFactoryPinIsGone() {
        runner.withUserConfiguration(ProviderWithoutSessionFactory.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(RosterFilterInstaller.SESSION_FACTORY_FIELD)
                        .hasMessageContaining("roster-filter.enabled=false"));
    }

    @Test // I24 — the factory is there but carries no 'requestHandlers' map: the second pin moved
    void failsStartupWhenTheRequestHandlersPinIsGone() {
        runner.withUserConfiguration(ProviderWithoutRequestHandlers.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(RosterFilterInstaller.REQUEST_HANDLERS_FIELD)
                        .hasMessageContaining("java-sdk #578"));
    }

    @Test // I24 — the map is there but has no "tools/list" entry: the method key moved
    void failsStartupWhenTheToolsListEntryIsAbsent() {
        runner.withUserConfiguration(ProviderWithoutToolsListEntry.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(McpSchema.METHOD_TOOLS_LIST));
    }

    @Test // the protocol pin: no streamable provider at all means the server is on legacy SSE
    void failsStartupNamingTheProtocolPropertyWhenNoStreamableProviderExists() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.ai.mcp.server.protocol=STREAMABLE"));
    }

    @Test // I20 — the escape hatch: OFF skips the installation AND its smoke check, so a moved pin boots
    void theKillSwitchOffSkipsTheInstallerAndItsSmokeCheckEntirely() {
        new ApplicationContextRunner()
                .withPropertyValues("example.mcp.authz.roster-filter.enabled=false")
                // The REAL @Configuration, so the @ConditionalOnProperty under test is the shipped one.
                .withUserConfiguration(
                        ToolAuthorizationConfiguration.class, ProviderWithoutSessionFactory.class)
                .withBean(ToolRegistry.class, () -> new ToolRegistry(List.of()))
                .withBean(OpaClient.class, DenyAllOpaClient::new)
                .withBean(tools.jackson.databind.ObjectMapper.class,
                        tools.jackson.databind.ObjectMapper::new)
                .withBean(dev.dmitriikonovalov.example.mcp.identity.DelegationChainExtractor.class,
                        () -> subject -> new dev.dmitriikonovalov.example.mcp.identity.DelegationChain(
                                subject.id(), null, List.of()))
                .withBean(dev.dmitriikonovalov.example.mcp.identity.AgentCapabilitySupplier.class,
                        () -> actorId -> null)
                .run(context -> {
                    assertThat(context)
                            .as("a provider whose pins are gone must NOT fail the context when OFF — "
                                    + "that is the whole point of the escape hatch")
                            .hasNotFailed();
                    assertThat(context).doesNotHaveBean(RosterFilterInstaller.class);
                });
    }

    @Test // the happy path: a matching seam installs, and the context starts
    void installsAndStartsWhenTheSeamMatches() {
        runner.withUserConfiguration(MatchingProvider.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MatchingProvider.StubProvider provider =
                            (MatchingProvider.StubProvider)
                                    context.getBean(McpStreamableServerTransportProvider.class);
                    assertThat(provider.sessionFactory.requestHandlers.get(McpSchema.METHOD_TOOLS_LIST))
                            .as("the installer wraps the entry the SDK would invoke, in place")
                            .isInstanceOf(RosterFilterInstaller.RosterFilteringHandler.class);
                });
    }

    // --- fixtures ---------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class FilterFixture {

        @Bean
        ToolRosterFilter toolRosterFilter() {
            ToolAuthorizationProperties properties = new ToolAuthorizationProperties();
            ToolRegistry registry = new ToolRegistry(
                    List.of(new ToolDescriptor("list_catalogs", "list", "READ", "catalog", Set.of("low"))));
            return new ToolRosterFilter(registry, null, new DenyAllOpaClient(), properties);
        }

        @Bean
        RosterFilterInstaller rosterFilterInstaller(
                ObjectProvider<McpStreamableServerTransportProvider> providers, ToolRosterFilter filter) {
            return new RosterFilterInstaller(providers, filter);
        }
    }

    /** The first pin is gone — no {@code sessionFactory} field anywhere on the provider. */
    @Configuration(proxyBeanMethods = false)
    static class ProviderWithoutSessionFactory {

        @Bean
        McpStreamableServerTransportProvider provider() {
            return new StubProvider();
        }

        static class StubProvider extends BaseStubProvider {
            @SuppressWarnings("unused")
            private final String somethingElse = "the field was renamed by an upgrade";
        }
    }

    /** The first pin resolves, the second does not. */
    @Configuration(proxyBeanMethods = false)
    static class ProviderWithoutRequestHandlers {

        @Bean
        McpStreamableServerTransportProvider provider() {
            return new StubProvider();
        }

        static class StubProvider extends BaseStubProvider {
            @SuppressWarnings("unused")
            private final Object sessionFactory = new Object();
        }
    }

    /** Both pins resolve, but the map no longer carries a {@code "tools/list"} entry. */
    @Configuration(proxyBeanMethods = false)
    static class ProviderWithoutToolsListEntry {

        @Bean
        McpStreamableServerTransportProvider provider() {
            return new StubProvider();
        }

        static class StubProvider extends BaseStubProvider {
            @SuppressWarnings("unused")
            private final Object sessionFactory = new EmptyFactory();
        }

        static class EmptyFactory {
            @SuppressWarnings("unused")
            final Map<String, McpRequestHandler<?>> requestHandlers = new HashMap<>();
        }
    }

    /** The seam as SDK 2.0.0 really shapes it — resolved by INTERFACE type, which is why this works. */
    @Configuration(proxyBeanMethods = false)
    static class MatchingProvider {

        @Bean
        McpStreamableServerTransportProvider provider() {
            return new StubProvider();
        }

        static class StubProvider extends BaseStubProvider {
            final SessionFactoryStub sessionFactory = new SessionFactoryStub();
        }

        static class SessionFactoryStub {
            final Map<String, McpRequestHandler<?>> requestHandlers = new HashMap<>(
                    Map.of(McpSchema.METHOD_TOOLS_LIST,
                            (McpRequestHandler<McpSchema.ListToolsResult>) (exchange, params) ->
                                    Mono.just(new McpSchema.ListToolsResult(List.of(), null, null))));
        }
    }

    /** Only what the interface demands; the pins are what each subclass varies. */
    abstract static class BaseStubProvider implements McpStreamableServerTransportProvider {

        @Override
        public void setSessionFactory(McpStreamableServerSession.Factory factory) {
            // Nothing to do: these stubs exist to be reflected into, not to serve traffic.
        }

        @Override
        public Mono<Void> notifyClients(String method, Object params) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }
    }

    private static final class DenyAllOpaClient implements OpaClient {

        @Override
        public boolean allow(AbacContext context) {
            return false;
        }

        @Override
        public PartialResult compile(AbacContext context) {
            return PartialResult.denyAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return contexts.stream().map(context -> Boolean.FALSE).toList();
        }
    }
}
