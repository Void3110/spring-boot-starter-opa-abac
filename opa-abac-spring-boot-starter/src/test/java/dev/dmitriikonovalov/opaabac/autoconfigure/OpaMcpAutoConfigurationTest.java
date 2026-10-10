package dev.dmitriikonovalov.opaabac.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.withSettings;

import com.sun.net.httpserver.HttpServer;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PerTypePolicyPathResolver;
import dev.dmitriikonovalov.opaabac.core.PolicyPathResolver;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.mcp.authz.MemoizingPrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.authz.PrincipalCeilingSupplier;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallAuthorizer;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolCallGate;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolPolicyPathResolver;
import dev.dmitriikonovalov.opaabac.mcp.authz.ToolRosterFilter;
import dev.dmitriikonovalov.opaabac.mcp.identity.ActorClaimWiringCheck;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilityProfile;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilitySupplier;
import dev.dmitriikonovalov.opaabac.mcp.identity.ClaimDelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.DelegationChain;
import dev.dmitriikonovalov.opaabac.mcp.identity.DelegationChainExtractor;
import dev.dmitriikonovalov.opaabac.mcp.identity.TurnScopedCapabilityCache;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.context.annotation.RequestScope;
import reactor.core.publisher.Mono;

/**
 * {@link ApplicationContextRunner} slice tests for {@link OpaMcpAutoConfiguration} (MCP-AUTH-LIBRARY T3/T4,
 * U9–U17, U35–U37).
 *
 * <h2>The baseline</h2>
 * Every fixture starts from {@code opa.abac.subject.attribute-claims=act_chain} + an {@code OpaClient}
 * pointed at an in-process {@link HttpServer} stub (the starter's own, via {@code OpaAbacAutoConfiguration})
 * + a {@link PrincipalCeilingSupplier} + an <strong>empty</strong> {@link ToolRegistry}; a non-empty
 * specification list is only ever contributed together with matching declarations. A case that proves one
 * failure removes exactly one baseline piece, so the failure it asserts cannot come from another guard, and
 * every startup failure is asserted by <em>this slice's own message text</em> along the cause chain.
 */
class OpaMcpAutoConfigurationTest {

    private static final RoleDefinition CEILING = new RoleDefinition(
            "type-level", Map.of(), Map.of("catalog", List.of("READ")), Map.of(), Map.of(), null);

    private HttpServer opaStub;
    private final List<String> opaPaths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startOpaStub() throws IOException {
        opaStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        opaStub.createContext("/", exchange -> {
            opaPaths.add(exchange.getRequestURI().getPath());
            String body = exchange.getRequestURI().getPath().endsWith("/bulk")
                    ? "{\"result\":[true]}"
                    : "{\"result\":{\"allow\":true}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        opaStub.start();
    }

    @AfterEach
    void stopOpaStub() {
        opaStub.stop(0);
        Counters.reset();
    }

    private String opaUrl() {
        return "http://127.0.0.1:" + opaStub.getAddress().getPort();
    }

    /** The full baseline: the starter (OpaClient), the MCP auto-configuration, a ceiling, an empty registry. */
    private ApplicationContextRunner baseline() {
        return baselineWithRegistry(EmptyRegistry.class);
    }

    /**
     * The baseline with a fixture that brings its OWN registry in place of the empty one — a non-empty
     * specification list only ever arrives together with its declarations.
     */
    private ApplicationContextRunner baselineWithRegistry(Class<?> fixtureWithRegistry) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OpaAbacAutoConfiguration.class, OpaMcpAutoConfiguration.class))
                .withPropertyValues("opa.abac.subject.attribute-claims=act_chain", "opa.abac.base-url=" + opaUrl())
                .withUserConfiguration(CeilingSource.class, fixtureWithRegistry);
    }

    /** The baseline minus the OpaClient: the starter's own auto-configuration is simply not there. */
    private ApplicationContextRunner baselineWithoutOpaClient() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpaMcpAutoConfiguration.class))
                .withPropertyValues("opa.abac.subject.attribute-claims=act_chain")
                .withUserConfiguration(CeilingSource.class, EmptyRegistry.class);
    }

    private static ApplicationContextRunner lazy(ApplicationContextRunner runner) {
        return runner
                .withInitializer(c -> c.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .withUserConfiguration(LazyProbe.class);
    }

    /** Every message along the cause chain — the failure a SmartInitializingSingleton throws is unwrapped. */
    private static String failureMessages(AssertableApplicationContext context) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }

    // --- U9 : activation --------------------------------------------------------------------------

    @Test // U9 — module + Spring AI on the classpath ⇒ the gate's beans, with the YAML capability default
    void activatesWithTheModuleAndSpringAiOnTheClasspath() {
        baseline().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ToolCallAuthorizer.class)
                    .hasSingleBean(ToolRosterFilter.class)
                    .hasSingleBean(ActorClaimWiringCheck.class);
            assertThat(context.getBean(DelegationChainExtractor.class))
                    .isInstanceOf(ClaimDelegationChainExtractor.class);
            AgentCapabilitySupplier capabilities = context.getBean(AgentCapabilitySupplier.class);
            assertThat(capabilities).isInstanceOf(TurnScopedCapabilityCache.class);
            assertThat(context.getBean("agentCapabilitySupplier")).isSameAs(capabilities);
            // The YAML default under the memo: an unconfigured agent is authoritative-empty.
            assertThat(capabilities.lookup("nobody")).isEqualTo(AgentCapabilityProfile.empty());
        });
    }

    @Test // U9 — Spring AI hidden ⇒ none of the gate's beans
    void backsOffWithoutSpringAi() {
        baseline().withClassLoader(new FilteredClassLoader("org.springframework.ai"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ToolCallAuthorizer.class)
                            .doesNotHaveBean(ToolRosterFilter.class)
                            .doesNotHaveBean(DelegationChainExtractor.class)
                            .doesNotHaveBean(AgentCapabilitySupplier.class)
                            .doesNotHaveBean(ActorClaimWiringCheck.class);
                });
    }

    @Test // U9 — the module hidden ⇒ none of them (no fixture here may reference a module type)
    void backsOffWithoutTheModule() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OpaAbacAutoConfiguration.class, OpaMcpAutoConfiguration.class))
                .withPropertyValues("opa.abac.base-url=" + opaUrl())
                .withClassLoader(new FilteredClassLoader("dev.dmitriikonovalov.opaabac.mcp"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("toolCallAuthorizer")
                            .doesNotHaveBean("toolRosterFilter")
                            .doesNotHaveBean("delegationChainExtractor")
                            .doesNotHaveBean("agentCapabilitySupplier")
                            .doesNotHaveBean("actorClaimWiringCheck")
                            .doesNotHaveBean("opaMcpStartupVerifier");
                });
    }

    // --- U10 / U11 : the prerequisites -------------------------------------------------------------

    @Test // U10 — no OpaClient ⇒ the context fails, naming opa.abac.enabled and OpaClient
    void failsWithoutAnOpaClient() {
        baselineWithoutOpaClient().run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context)).contains("opa.abac.enabled").contains("OpaClient");
        });
    }

    @Test // U10 + fork 16 — the starter OFF with ADR 0038's acknowledgment still never serves tools ungated
    void failsWithTheStarterOffEvenUnderTheUngatedMethodsAcknowledgment() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OpaAbacAutoConfiguration.class, OpaAbacDisabledAutoConfiguration.class,
                        OpaMcpAutoConfiguration.class))
                .withPropertyValues(
                        "opa.abac.enabled=false",
                        "opa.abac.allow-ungated-methods=true",
                        "opa.abac.subject.attribute-claims=act_chain")
                .withUserConfiguration(CeilingSource.class, EmptyRegistry.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context))
                            .contains("opa.abac.enabled")
                            .contains("OpaClient")
                            .contains("opa.abac.allow-ungated-methods does not apply to MCP tools");
                });
    }

    @Test // U11 — no PrincipalCeilingSupplier ⇒ fails naming the SPI (there is no default)
    void failsWithoutAPrincipalCeilingSupplier() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OpaAbacAutoConfiguration.class, OpaMcpAutoConfiguration.class))
                .withPropertyValues("opa.abac.subject.attribute-claims=act_chain", "opa.abac.base-url=" + opaUrl())
                .withUserConfiguration(EmptyRegistry.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context)).contains("PrincipalCeilingSupplier");
                });
    }

    // --- U12 : adopter beans win -------------------------------------------------------------------

    @Test // U12 — an adopter capability source replaces the YAML default and is still wrapped by the memo
    void anAdopterCapabilitySupplierWinsAndIsStillMemoized() {
        baseline().withUserConfiguration(AdopterCapability.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(AgentCapabilitySupplier.class))
                    .containsExactly("adopterCapability");
            AgentCapabilitySupplier capabilities = context.getBean(AgentCapabilitySupplier.class);
            assertThat(capabilities).isInstanceOf(TurnScopedCapabilityCache.class);
            assertThat(capabilities.lookup("any")).isEqualTo(AdopterCapability.PROFILE);
        });
    }

    @Test // U12 — an adopter extractor ⇒ the default backs off
    void anAdopterExtractorWins() {
        baseline().withUserConfiguration(AdopterExtractor.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(DelegationChainExtractor.class))
                    .containsExactly("adopterExtractor");
            assertThat(context.getBean(DelegationChainExtractor.class))
                    .isNotInstanceOf(ClaimDelegationChainExtractor.class);
        });
    }

    // --- U13 : the policy-path decorator -----------------------------------------------------------

    private static AbacContext contextOf(String type) {
        return new AbacContext(
                new AbacContext.Subject("alice", List.of(), Map.of()),
                "view",
                new AbacContext.Resource(type, "r-1", Map.of(), List.of()),
                null,
                Map.of());
    }

    @Test // U13 — tool ⇒ the tool-gate package; any other type ⇒ exactly the undecorated resolver's answer
    void decoratesTheApplicationsResolverInsteadOfReplacingIt() {
        baseline().withPropertyValues("opa.abac.policy-prefix=abac").run(context -> {
            PolicyPathResolver resolver = context.getBean(PolicyPathResolver.class);
            assertThat(resolver).isInstanceOf(ToolPolicyPathResolver.class);
            assertThat(resolver.resolve(contextOf("tool"))).isEqualTo("agent_tools");
            assertThat(resolver.resolve(contextOf("catalog")))
                    .isEqualTo(new PerTypePolicyPathResolver("abac").resolve(contextOf("catalog")))
                    .isEqualTo("abac/catalog");

            // The context's one OpaClient was built from the DECORATED resolver.
            OpaClient opa = context.getBean(OpaClient.class);
            opa.allow(contextOf("tool"));
            opa.allowAll(List.of(contextOf("tool")));
            assertThat(opaPaths).containsExactly("/v1/data/agent_tools", "/v1/data/agent_tools/bulk");
        });
    }

    @Test // U13 — the configured package path
    void routesToolDecisionsToTheConfiguredPolicyPath() {
        baseline().withPropertyValues("opa.abac.mcp.policy-path=x/y").run(context ->
                assertThat(context.getBean(PolicyPathResolver.class).resolve(contextOf("tool")))
                        .isEqualTo("x/y"));
    }

    // --- U14 : the ceiling memo --------------------------------------------------------------------

    @Test // U14 — the context's ceiling source is memoized by default
    void theCeilingIsMemoizedByDefault() {
        baseline().run(context -> assertThat(context.getBean(PrincipalCeilingSupplier.class))
                .isInstanceOf(MemoizingPrincipalCeilingSupplier.class));
    }

    @Test // U14 — the role-definition memo's switch governs the ceiling memo too
    void theResolveMemoSwitchOffServesTheRawCeilingSource() {
        baseline().withPropertyValues("opa.abac.resolve-memo.enabled=false").run(context ->
                assertThat(context.getBean(PrincipalCeilingSupplier.class))
                        .isNotInstanceOf(MemoizingPrincipalCeilingSupplier.class)
                        .isSameAs(context.getBean("ceilingSource")));
    }

    // --- U15 : every list, every element -----------------------------------------------------------

    @Test // U15 — two sync lists, an element at index > 0, a pre-gated element kept as is, other lists untouched
    void gatesEveryElementOfEverySyncSpecificationList() {
        baselineWithRegistry(TwoSyncListsDeclared.class).run(context -> {
            assertThat(context).hasNotFailed();
            @SuppressWarnings("unchecked")
            List<SyncToolSpecification> first = (List<SyncToolSpecification>) context.getBean("firstTools");
            @SuppressWarnings("unchecked")
            List<SyncToolSpecification> second = (List<SyncToolSpecification>) context.getBean("secondTools");

            assertThat(first).hasSize(2)
                    .allSatisfy(spec -> assertThat(spec.callHandler()).isInstanceOf(ToolCallGate.class));
            assertThat(second).hasSize(2)
                    .allSatisfy(spec -> assertThat(spec.callHandler()).isInstanceOf(ToolCallGate.class));
            // index 1 of the second list was already gated: kept, never wrapped a second time
            assertThat(second.get(1).callHandler()).isSameAs(TwoSyncListsDeclared.PRE_GATED_HANDLER);

            assertThat(context.getBean("names")).isEqualTo(List.of("a", "b"));
            @SuppressWarnings("unchecked")
            List<Object> resources = (List<Object>) context.getBean("resources");
            assertThat(resources).singleElement().isSameAs(TwoSyncListsDeclared.RESOURCE);
        });
    }

    // --- U16 : the wiring check reads the environment ----------------------------------------------

    @Test // U16 — the actor claim must be copied; checked without ever consulting the starter's properties bean
    void theWiringCheckReadsTheEnvironmentNotTheStartersPropertiesBean() {
        // A mock that records WHO calls it. Spring's own configuration-properties binder does touch it (the
        // bean's type carries @ConfigurationProperties); what must never touch it is the tool-gate's code.
        List<String> callers = new CopyOnWriteArrayList<>();
        OpaAbacProperties replaced = mock(OpaAbacProperties.class, withSettings().defaultAnswer(invocation -> {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                callers.add(frame.getClassName());
            }
            return RETURNS_DEFAULTS.answer(invocation);
        }));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpaMcpAutoConfiguration.class))
                .withPropertyValues(
                        "opa.abac.mcp.identity.actor-claim=act_chain",
                        "opa.abac.subject.attribute-claims=azp")
                .withBean(OpaAbacProperties.class, () -> replaced)
                .withBean(OpaClient.class, StubOpaClient::new)
                .withUserConfiguration(CeilingSource.class, EmptyRegistry.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context))
                            .contains("opa.abac.mcp.identity.actor-claim")
                            .contains("opa.abac.subject.attribute-claims");
                    assertThat(callers)
                            .as("the wiring check and the auto-configuration never consult the properties bean")
                            .noneMatch(caller -> caller.startsWith("dev.dmitriikonovalov.opaabac.mcp.")
                                    || caller.equals(OpaMcpAutoConfiguration.class.getName())
                                    || caller.startsWith(OpaMcpAutoConfiguration.class.getName() + "$"));
                });
    }

    // --- U35 : part 0 never serves an unwrapped type -----------------------------------------------

    @Test // U35 — the stateful async type is refused, naming it and the supported one
    void refusesAnAsyncStatefulSpecificationList() {
        baselineWithRegistry(AsyncStatefulDeclared.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context))
                    .contains(McpServerFeatures.AsyncToolSpecification.class.getName())
                    .contains("supported: McpServerFeatures.SyncToolSpecification");
        });
    }

    @Test // U35 — the stateless sync type is refused
    void refusesAStatelessSyncSpecificationList() {
        baselineWithRegistry(StatelessSyncDeclared.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context))
                    .contains(McpStatelessServerFeatures.SyncToolSpecification.class.getName())
                    .contains("supported: McpServerFeatures.SyncToolSpecification");
        });
    }

    @Test // U35 — the stateless async type is refused
    void refusesAStatelessAsyncSpecificationList() {
        baselineWithRegistry(StatelessAsyncDeclared.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context))
                    .contains(McpStatelessServerFeatures.AsyncToolSpecification.class.getName())
                    .contains("supported: McpServerFeatures.SyncToolSpecification");
        });
    }

    @Test // U35 — a type nobody has heard of (a future fifth) is recognized by its tool() accessor and refused
    void refusesAnUnknownToolBearingType() {
        baselineWithRegistry(FutureSpecificationDeclared.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context))
                    .contains(FutureSpecification.class.getName())
                    .contains("supported: McpServerFeatures.SyncToolSpecification");
        });
    }

    @Test // U35 — a list that never passed the post-processor (registerSingleton) is caught by the verifier
    void refusesASyncListThatNeverPassedTheGate() {
        baseline()
                .withInitializer(c -> c.getBeanFactory().registerSingleton(
                        "smuggledTools", new ArrayList<>(List.of(syncSpec("smuggled")))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context))
                            .contains("smuggledTools")
                            .contains("without the opa-abac-mcp tool-gate");
                });
    }

    // --- U36 : the guards are eager ----------------------------------------------------------------

    @Test // U36 — the lazy fixture really is lazy: an unrequested bean is never created
    void theLazyFixtureIsReallyLazy() {
        lazy(baseline()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(Counters.PROBE.get()).as("an unrequested bean under lazy initialization").isZero();
        });
    }

    @Test // U36 — U10 under lazy initialization
    void theOpaClientCheckIsEagerUnderLazyInitialization() {
        lazy(baselineWithoutOpaClient()).run(context -> {
            assertThat(Counters.PROBE.get()).isZero();
            assertThat(context).hasFailed();
            assertThat(failureMessages(context)).contains("opa.abac.enabled").contains("OpaClient");
        });
    }

    @Test // U36 — U11 under lazy initialization
    void theCeilingCheckIsEagerUnderLazyInitialization() {
        lazy(new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OpaAbacAutoConfiguration.class, OpaMcpAutoConfiguration.class))
                .withPropertyValues("opa.abac.subject.attribute-claims=act_chain", "opa.abac.base-url=" + opaUrl())
                .withUserConfiguration(EmptyRegistry.class))
                .run(context -> {
                    assertThat(Counters.PROBE.get()).isZero();
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context)).contains("PrincipalCeilingSupplier");
                });
    }

    @Test // U36 — U16 under lazy initialization
    void theWiringCheckIsEagerUnderLazyInitialization() {
        lazy(baseline()).withPropertyValues("opa.abac.subject.attribute-claims=azp").run(context -> {
            assertThat(Counters.PROBE.get()).isZero();
            assertThat(context).hasFailed();
            assertThat(failureMessages(context)).contains("opa.abac.mcp.identity.actor-claim");
        });
    }

    @Test // U36 — U35 under lazy initialization: the unsupported list is resolved by the verifier, not injected
    void anUnsupportedListNothingInjectsIsStillRefusedUnderLazyInitialization() {
        lazy(baselineWithRegistry(AsyncStatefulDeclared.class)).run(context -> {
            assertThat(Counters.PROBE.get()).isZero();
            assertThat(context).hasFailed();
            assertThat(failureMessages(context))
                    .contains(McpServerFeatures.AsyncToolSpecification.class.getName());
        });
    }

    @Test // U36 — request- and prototype-scoped lists are never instantiated by the verifier, and do not fail
    void scopedAndPrototypeListsAreNeverTouchedUnderLazyInitialization() {
        lazy(baseline()).withUserConfiguration(ScopedLists.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(Counters.REQUEST_LIST.get()).isZero();
            assertThat(Counters.PROTOTYPE_LIST.get()).isZero();
        });
    }

    @Test // U36 — without lazy init, a @RequestScope proxy nothing injects starts: only step 2's skip keeps it out
    void aRequestScopedProxyListNothingInjectsStillStarts() {
        baseline().withUserConfiguration(RequestScopedProxyList.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(Counters.REQUEST_PROXY_LIST.get()).isZero();
        });
    }

    @Test // layer-2 — only a REAL scoped proxy is exempt: a singleton list whose name merely has a
    // 'scopedTarget.' namesake is still gated and counted, never passed through
    void aScopedTargetNamesakeDoesNotExemptASingletonToolList() {
        baselineWithRegistry(ScopedTargetNamesake.class).run(context -> {
            assertThat(context).hasNotFailed();
            @SuppressWarnings("unchecked")
            List<SyncToolSpecification> lookalike = (List<SyncToolSpecification>) context.getBean("lookalike");
            assertThat(lookalike).singleElement()
                    .satisfies(spec -> assertThat(spec.callHandler()).isInstanceOf(ToolCallGate.class));
        });
    }

    // --- U17 / U37 : declarations checked against what was wrapped (T4) ---------------------------

    @Test // U17 — wrapped ⊋ registry: an advertised tool with no declaration fails, the message names it
    void failsWhenAWrappedToolIsUndeclared() {
        baselineWithRegistry(UndeclaredTool.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context))
                    .contains("Advertised but undeclared (a tool with no action/category/risk to authorize against): [b]")
                    .contains("Declared but never wrapped")
                    .contains(": [].");
        });
    }

    @Test // U17 — registry ⊋ wrapped: a declaration the gate never wrapped fails, the message names it
    void failsWhenADeclaredToolWasNeverWrapped() {
        baselineWithRegistry(PhantomDeclaration.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureMessages(context))
                    .contains("Advertised but undeclared (a tool with no action/category/risk to authorize against): []")
                    .contains("Declared but never wrapped")
                    .contains("[phantom]");
        });
    }

    @Test // U17 — names spread over two lists that together equal the registry start
    void startsWhenTheUnionOfAllListsEqualsTheRegistry() {
        baselineWithRegistry(TwoSyncListsDeclared.class).run(context -> assertThat(context).hasNotFailed());
    }

    @Test // U17 — no ToolRegistry bean fails, naming it
    void failsWithoutAToolRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OpaAbacAutoConfiguration.class, OpaMcpAutoConfiguration.class))
                .withPropertyValues("opa.abac.subject.attribute-claims=act_chain", "opa.abac.base-url=" + opaUrl())
                .withUserConfiguration(CeilingSource.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context)).contains("needs a ToolRegistry bean");
                });
    }

    @Test // U17 — an empty registry and no tools: a server with no tools starts
    void startsWithAnEmptyRegistryAndNoTools() {
        baseline().run(context -> assertThat(context).hasNotFailed());
    }

    @Test // U37 — under lazy initialization a matching list nothing injects is still wrapped and counted
    void aLazyListNothingInjectsIsWrappedAndCounted() {
        lazy(baselineWithRegistry(LazyPairDeclared.class)).run(context -> {
            assertThat(Counters.PROBE.get()).isZero();
            assertThat(context).hasNotFailed();
            @SuppressWarnings("unchecked")
            List<SyncToolSpecification> pair = (List<SyncToolSpecification>) context.getBean("lazyPair");
            assertThat(pair).hasSize(2)
                    .allSatisfy(spec -> assertThat(spec.callHandler()).isInstanceOf(ToolCallGate.class));
        });
    }

    @Test // U37 — and a third declaration with no tool behind it still fails, naming it
    void aLazyContextStillFailsOnADeclarationNothingServes() {
        lazy(baselineWithRegistry(LazyPairWithAThirdDeclaration.class)).run(context -> {
            assertThat(Counters.PROBE.get()).isZero();
            assertThat(context).hasFailed();
            assertThat(failureMessages(context)).contains("Declared but never wrapped").contains("[third]");
        });
    }

    // --- fixtures ----------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class ScopedTargetNamesake {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(declared("look")));
        }

        @Bean
        List<SyncToolSpecification> lookalike() {
            return new ArrayList<>(List.of(syncSpec("look")));
        }

        @Bean(name = "scopedTarget.lookalike")
        Object unrelatedNamesake() {
            return new Object();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UndeclaredTool {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(declared("a")));
        }

        @Bean
        List<SyncToolSpecification> tools() {
            return new ArrayList<>(List.of(syncSpec("a"), syncSpec("b")));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PhantomDeclaration {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(declared("a"), declared("phantom")));
        }

        @Bean
        List<SyncToolSpecification> tools() {
            return new ArrayList<>(List.of(syncSpec("a")));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyPairDeclared {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(declared("first"), declared("second")));
        }

        @Bean
        List<SyncToolSpecification> lazyPair() {
            return new ArrayList<>(List.of(syncSpec("first"), syncSpec("second")));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyPairWithAThirdDeclaration {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(declared("first"), declared("second"), declared("third")));
        }

        @Bean
        List<SyncToolSpecification> lazyPair() {
            return new ArrayList<>(List.of(syncSpec("first"), syncSpec("second")));
        }
    }

    static SyncToolSpecification syncSpec(String name) {
        return new SyncToolSpecification(tool(name), (exchange, request) -> CallToolResult.builder().build());
    }

    static Tool tool(String name) {
        return Tool.builder().name(name).inputSchema(Map.of("type", "object")).build();
    }

    static ToolDescriptor declared(String name) {
        return new ToolDescriptor(name, "view", "READ", "catalog", Set.of("low"));
    }

    static final class Counters {
        static final AtomicInteger PROBE = new AtomicInteger();
        static final AtomicInteger REQUEST_LIST = new AtomicInteger();
        static final AtomicInteger PROTOTYPE_LIST = new AtomicInteger();
        static final AtomicInteger REQUEST_PROXY_LIST = new AtomicInteger();

        static void reset() {
            PROBE.set(0);
            REQUEST_LIST.set(0);
            PROTOTYPE_LIST.set(0);
            REQUEST_PROXY_LIST.set(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CeilingSource {
        @Bean
        PrincipalCeilingSupplier ceilingSource() {
            return (subject, type) -> Optional.of(CEILING);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyRegistry {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of());
        }
    }

    /** An ordinary bean nothing requests — the proof that a fixture is really lazy. */
    @Configuration(proxyBeanMethods = false)
    static class LazyProbe {
        @Bean
        Object lazyProbe() {
            Counters.PROBE.incrementAndGet();
            return new Object();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AdopterCapability {
        static final AgentCapabilityProfile PROFILE =
                new AgentCapabilityProfile(Set.of("READ"), Set.of("t"), Set.of("view"), "low");

        @Bean
        AgentCapabilitySupplier adopterCapability() {
            return actorId -> PROFILE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AdopterExtractor {
        @Bean
        DelegationChainExtractor adopterExtractor() {
            return subject -> DelegationChain.ofPrincipal(subject.id());
        }
    }

    /** Two sync lists whose tools together equal the declarations; plus two non-tool lists. */
    @Configuration(proxyBeanMethods = false)
    static class TwoSyncListsDeclared {
        static final ToolCallGate PRE_GATED_HANDLER = (ToolCallGate) ToolCallGate.gate(
                syncSpec("d"), new ToolCallAuthorizer(null, null, null, null, null, null)).callHandler();
        static final McpServerFeatures.SyncResourceSpecification RESOURCE =
                new McpServerFeatures.SyncResourceSpecification(
                        McpSchema.Resource.builder().uri("res://x").name("x").build(), (exchange, request) -> null);

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(declared("a"), declared("b"), declared("c"), declared("d")));
        }

        @Bean
        List<SyncToolSpecification> firstTools() {
            return new ArrayList<>(List.of(syncSpec("a"), syncSpec("b")));
        }

        @Bean
        List<SyncToolSpecification> secondTools() {
            return new ArrayList<>(List.of(syncSpec("c"), new SyncToolSpecification(tool("d"), PRE_GATED_HANDLER)));
        }

        @Bean
        List<String> names() {
            return List.of("a", "b");
        }

        @Bean
        List<Object> resources() {
            return List.of(RESOURCE);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AsyncStatefulDeclared {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(declared("async")));
        }

        @Bean
        List<McpServerFeatures.AsyncToolSpecification> asyncTools() {
            return List.of(new McpServerFeatures.AsyncToolSpecification(
                    tool("async"), (exchange, request) -> Mono.just(CallToolResult.builder().build())));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StatelessSyncDeclared {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(declared("stateless")));
        }

        @Bean
        List<McpStatelessServerFeatures.SyncToolSpecification> statelessTools() {
            return List.of(new McpStatelessServerFeatures.SyncToolSpecification(
                    tool("stateless"), (transport, request) -> CallToolResult.builder().build()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StatelessAsyncDeclared {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(declared("stateless-async")));
        }

        @Bean
        List<McpStatelessServerFeatures.AsyncToolSpecification> statelessAsyncTools() {
            return List.of(new McpStatelessServerFeatures.AsyncToolSpecification(
                    tool("stateless-async"), (transport, request) -> Mono.just(CallToolResult.builder().build())));
        }
    }

    /** A specification type no SDK release ships — recognized only by its {@code tool()} accessor. */
    record FutureSpecification(Tool tool) {
    }

    @Configuration(proxyBeanMethods = false)
    static class FutureSpecificationDeclared {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(declared("future")));
        }

        @Bean
        List<FutureSpecification> futureTools() {
            return List.of(new FutureSpecification(tool("future")));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ScopedLists {
        @Bean
        @Scope("request")
        List<SyncToolSpecification> requestTools() {
            Counters.REQUEST_LIST.incrementAndGet();
            return List.of(syncSpec("request-scoped"));
        }

        @Bean
        @Scope("prototype")
        List<SyncToolSpecification> prototypeTools() {
            Counters.PROTOTYPE_LIST.incrementAndGet();
            return List.of(syncSpec("prototype"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RequestScopedProxyList {
        @Bean
        @RequestScope
        List<SyncToolSpecification> requestProxyTools() {
            Counters.REQUEST_PROXY_LIST.incrementAndGet();
            return new ArrayList<>(List.of(syncSpec("request-proxy")));
        }
    }

    /** OpaClient has three methods, so it cannot be a lambda. Allows nothing. */
    static final class StubOpaClient implements OpaClient {
        @Override
        public boolean allow(AbacContext context) {
            return false;
        }

        @Override
        public dev.dmitriikonovalov.opaabac.core.PartialResult compile(AbacContext context) {
            return dev.dmitriikonovalov.opaabac.core.PartialResult.denyAll();
        }

        @Override
        public List<Boolean> allowAll(List<AbacContext> contexts) {
            return contexts.stream().map(c -> Boolean.FALSE).toList();
        }
    }
}
