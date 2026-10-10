package dev.dmitriikonovalov.opaabac.mcp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.OpaClient;
import dev.dmitriikonovalov.opaabac.core.PartialResult;
import dev.dmitriikonovalov.opaabac.mcp.identity.AgentCapabilityProfile;
import dev.dmitriikonovalov.opaabac.mcp.identity.DelegationChain;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolDescriptor;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolErrorLayer;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolFailureRecord;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolInvocationException;
import dev.dmitriikonovalov.opaabac.mcp.tool.ToolRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Mono;

/**
 * The three tool-gate wrappers T5 adds — stateful async (U20), stateless sync (U21) and stateless async
 * (U22) — and the deny-all of every wrapper with no authenticated caller (U25's module half).
 *
 * <p>The two async wrappers share every case, so they run as one parameterized set: the shape under test
 * is a function from "the delegate's {@code Mono}-producing body" to "the wrapper's returned {@code Mono}",
 * built through each wrapper's own public {@code gate(…)} so the specification wiring is exercised too.
 */
class ToolGateWrappersTest {

    private static final String TOOL = "probe_tool";
    private static final CallToolRequest REQUEST = new CallToolRequest(TOOL, Map.of());
    private static final CallToolResult OK = new CallToolResult(
            List.of(new TextContent(null, "ok", null)), Boolean.FALSE, null, Map.of());
    private static final CallToolResult FLATTENED_ERROR = new CallToolResult(
            List.of(new TextContent(null, "Error invoking method", null)), Boolean.TRUE, null, Map.of());

    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void unbind() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** An authorizer with a fixed answer that counts how often it is asked. */
    private static final class CountingAuthorizer extends ToolCallAuthorizer {
        final AtomicInteger calls = new AtomicInteger();
        private final ToolAuthorizationDecision answer;

        CountingAuthorizer(ToolAuthorizationDecision answer) {
            super(null, null, null, null, null, null);
            this.answer = answer;
        }

        @Override
        public ToolAuthorizationDecision authorize(String toolName) {
            calls.incrementAndGet();
            return answer;
        }
    }

    private static CountingAuthorizer allowing() {
        return new CountingAuthorizer(ToolAuthorizationDecision.permitted());
    }

    private static CountingAuthorizer denying() {
        return new CountingAuthorizer(ToolAuthorizationDecision.denied(ToolCallAuthorizer.CODE_POLICY_DENIED));
    }

    private static Tool tool() {
        return Tool.builder().name(TOOL).inputSchema(Map.of("type", "object")).build();
    }

    /** One async shape: given an authorizer and the delegate body, the gated handler's returned Mono. */
    interface AsyncShape {
        Mono<CallToolResult> invoke(ToolCallAuthorizer authorizer, Function<CallToolRequest, Mono<CallToolResult>> body);
    }

    static Stream<AsyncShape> asyncShapes() {
        AsyncShape statefulAsync = new AsyncShape() {
            @Override
            public Mono<CallToolResult> invoke(
                    ToolCallAuthorizer authorizer, Function<CallToolRequest, Mono<CallToolResult>> body) {
                McpServerFeatures.AsyncToolSpecification gated = AsyncToolCallGate.gate(
                        new McpServerFeatures.AsyncToolSpecification(tool(), (exchange, request) -> body.apply(request)),
                        authorizer);
                assertThat(gated.callHandler()).isInstanceOf(AsyncToolCallGate.class);
                assertThat(gated.tool()).isEqualTo(tool());
                return gated.callHandler().apply(null, REQUEST);
            }

            @Override
            public String toString() {
                return "McpServerFeatures.AsyncToolSpecification";
            }
        };
        AsyncShape statelessAsync = new AsyncShape() {
            @Override
            public Mono<CallToolResult> invoke(
                    ToolCallAuthorizer authorizer, Function<CallToolRequest, Mono<CallToolResult>> body) {
                McpStatelessServerFeatures.AsyncToolSpecification gated = StatelessAsyncToolCallGate.gate(
                        new McpStatelessServerFeatures.AsyncToolSpecification(
                                tool(), (transport, request) -> body.apply(request)),
                        authorizer);
                assertThat(gated.callHandler()).isInstanceOf(StatelessAsyncToolCallGate.class);
                assertThat(gated.tool()).isEqualTo(tool());
                return gated.callHandler().apply(McpTransportContext.EMPTY, REQUEST);
            }

            @Override
            public String toString() {
                return "McpStatelessServerFeatures.AsyncToolSpecification";
            }
        };
        return Stream.of(statefulAsync, statelessAsync);
    }

    private static String metaOf(CallToolResult result, String key) {
        return result.meta() == null ? null : String.valueOf(result.meta().get(key));
    }

    // --- U20 / U22 : the async wrappers ---------------------------------------------------------

    @ParameterizedTest(name = "{0}") // deny ⇒ the advisory, and the delegate is never invoked
    @MethodSource("asyncShapes")
    void aDenialEmitsTheAdvisoryAndNeverInvokesTheDelegate(AsyncShape shape) {
        AtomicInteger delegateInvoked = new AtomicInteger();
        Mono<CallToolResult> mono = shape.invoke(denying(), request -> {
            delegateInvoked.incrementAndGet();
            return Mono.just(OK);
        });

        CallToolResult result = mono.block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(metaOf(result, "layer")).isEqualTo(ToolErrorLayer.TOOL_GATE.label());
        assertThat(metaOf(result, "code")).isEqualTo(ToolCallAuthorizer.CODE_POLICY_DENIED);
        assertThat(result.structuredContent()).isNull();
        mono.block();
        assertThat(delegateInvoked).as("a denied call never reaches the tool body").hasValue(0);
    }

    @ParameterizedTest(name = "{0}") // allow ⇒ the delegate's own result
    @MethodSource("asyncShapes")
    void anAllowEmitsTheDelegatesResult(AsyncShape shape) {
        assertThat(shape.invoke(allowing(), request -> Mono.just(OK)).block()).isSameAs(OK);
    }

    @ParameterizedTest(name = "{0}") // decided ONCE, at apply — a resubscription never asks again
    @MethodSource("asyncShapes")
    void theAuthorizerIsAskedOnceAtApplyNeverAtSubscription(AsyncShape shape) {
        CountingAuthorizer authorizer = allowing();
        AtomicInteger bodyRuns = new AtomicInteger();
        Mono<CallToolResult> mono = shape.invoke(authorizer, request -> Mono.fromSupplier(() -> {
            bodyRuns.incrementAndGet();
            return OK;
        }));

        assertThat(authorizer.calls).as("decided when the handler was invoked").hasValue(1);
        assertThat(bodyRuns).as("the deferred body has not run yet").hasValue(0);

        mono.block();
        mono.block();

        assertThat(bodyRuns).as("subscribed twice, the body ran twice").hasValue(2);
        assertThat(authorizer.calls).as("…and the decision was never re-taken inside the Mono").hasValue(1);
    }

    @ParameterizedTest(name = "{0}") // a ToolInvocationException thrown before any Mono ⇒ its advisory
    @MethodSource("asyncShapes")
    void aSynchronousToolInvocationExceptionBecomesItsAdvisory(AsyncShape shape) {
        CallToolResult result = shape.invoke(allowing(), request -> {
            throw new ToolInvocationException(ToolErrorLayer.TARGET_GATE, "catalog-forbidden", "no");
        }).block();

        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(metaOf(result, "layer")).isEqualTo("target-gate");
        assertThat(metaOf(result, "code")).isEqualTo("catalog-forbidden");
    }

    @ParameterizedTest(name = "{0}") // the same exception EMITTED by the Mono ⇒ the same advisory
    @MethodSource("asyncShapes")
    void anEmittedToolInvocationExceptionBecomesTheSameAdvisory(AsyncShape shape) {
        CallToolResult result = shape.invoke(allowing(), request -> Mono.error(
                new ToolInvocationException(ToolErrorLayer.TARGET_GATE, "catalog-forbidden", "no"))).block();

        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(metaOf(result, "layer")).isEqualTo("target-gate");
        assertThat(metaOf(result, "code")).isEqualTo("catalog-forbidden");
    }

    @ParameterizedTest(name = "{0}") // a deferred body that records its failure is relabelled AT EMISSION
    @MethodSource("asyncShapes")
    void aRecordedFailureIsRelabelledAtEmission(AsyncShape shape) {
        Mono<CallToolResult> mono = shape.invoke(allowing(), request -> Mono.fromSupplier(() -> {
            // What the annotation-scanned handler does: the body records, then the error is flattened.
            ToolFailureRecord.capture(
                    new ToolInvocationException(ToolErrorLayer.TARGET_GATE, "catalog-forbidden", "no"));
            return FLATTENED_ERROR;
        }));

        CallToolResult result = mono.block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(metaOf(result, "layer")).isEqualTo("target-gate");
        assertThat(metaOf(result, "code")).isEqualTo("catalog-forbidden");
    }

    @ParameterizedTest(name = "{0}") // no request bound at emission ⇒ the error result passes through as is
    @MethodSource("asyncShapes")
    void withNoRequestBoundAtEmissionTheErrorResultPassesThroughUnrelabelled(AsyncShape shape) {
        Mono<CallToolResult> mono = shape.invoke(allowing(), request -> Mono.fromSupplier(() -> {
            ToolFailureRecord.capture(
                    new ToolInvocationException(ToolErrorLayer.TARGET_GATE, "catalog-forbidden", "no"));
            return FLATTENED_ERROR;
        }));
        RequestContextHolder.resetRequestAttributes();

        assertThat(mono.block()).isSameAs(FLATTENED_ERROR);
    }

    @ParameterizedTest(name = "{0}") // any other emitted error passes through UNCHANGED — never a success
    @MethodSource("asyncShapes")
    void anyOtherEmittedErrorPassesThroughUnchanged(AsyncShape shape) {
        IllegalStateException failure = new IllegalStateException("downstream broke");
        Mono<CallToolResult> mono = shape.invoke(allowing(), request -> Mono.error(failure));

        assertThatThrownBy(mono::block).isSameAs(failure);
    }

    @ParameterizedTest(name = "{0}") // an empty completion passes through unchanged
    @MethodSource("asyncShapes")
    void anEmptyCompletionPassesThroughUnchanged(AsyncShape shape) {
        Mono<CallToolResult> mono = shape.invoke(allowing(), request -> Mono.empty());

        assertThat(mono.hasElement().block()).isFalse();
    }

    @ParameterizedTest(name = "{0}") // a clean result is never relabelled, even with a stale record around
    @MethodSource("asyncShapes")
    void aSuccessfulResultIsLeftAlone(AsyncShape shape) {
        ToolFailureRecord.capture(new ToolInvocationException(ToolErrorLayer.TARGET_GATE, "stale", "stale"));

        assertThat(shape.invoke(allowing(), request -> Mono.just(OK)).block()).isSameAs(OK);
    }

    // --- U21 : the stateless sync wrapper -------------------------------------------------------

    private static CallToolResult invokeStatelessSync(
            ToolCallAuthorizer authorizer, Function<CallToolRequest, CallToolResult> body) {
        McpStatelessServerFeatures.SyncToolSpecification gated = StatelessToolCallGate.gate(
                new McpStatelessServerFeatures.SyncToolSpecification(tool(), (transport, request) -> body.apply(request)),
                authorizer);
        assertThat(gated.callHandler()).isInstanceOf(StatelessToolCallGate.class);
        assertThat(gated.tool()).isEqualTo(tool());
        return gated.callHandler().apply(McpTransportContext.EMPTY, REQUEST);
    }

    @Test // U21 — deny ⇒ the advisory, body never invoked
    void statelessSyncDenialNeverInvokesTheBody() {
        AtomicInteger bodyRuns = new AtomicInteger();
        CallToolResult result = invokeStatelessSync(denying(), request -> {
            bodyRuns.incrementAndGet();
            return OK;
        });

        assertThat(result.isError()).isTrue();
        assertThat(metaOf(result, "layer")).isEqualTo("tool-gate");
        assertThat(metaOf(result, "code")).isEqualTo(ToolCallAuthorizer.CODE_POLICY_DENIED);
        assertThat(bodyRuns).hasValue(0);
    }

    @Test // U21 — allow ⇒ the body's own result
    void statelessSyncAllowReturnsTheBodysResult() {
        assertThat(invokeStatelessSync(allowing(), request -> OK)).isSameAs(OK);
    }

    @Test // U21 — a structured tool failure ⇒ its advisory
    void statelessSyncToolInvocationExceptionBecomesItsAdvisory() {
        CallToolResult result = invokeStatelessSync(allowing(), request -> {
            throw new ToolInvocationException(ToolErrorLayer.TARGET_GATE, "catalog-forbidden", "no");
        });

        assertThat(metaOf(result, "layer")).isEqualTo("target-gate");
        assertThat(metaOf(result, "code")).isEqualTo("catalog-forbidden");
    }

    // --- U25 (module half) : no authenticated caller ⇒ every wrapper denies, body never invoked --

    /** The real authorizer, with collaborators that fail the test if the call ever got past identity. */
    private static ToolCallAuthorizer realAuthorizer() {
        OpaClient neverAsked = new OpaClient() {
            @Override
            public boolean allow(AbacContext context) {
                throw new AssertionError("an unauthenticated call must never reach the policy");
            }

            @Override
            public PartialResult compile(AbacContext context) {
                throw new AssertionError("not used");
            }

            @Override
            public List<Boolean> allowAll(List<AbacContext> contexts) {
                throw new AssertionError("not used");
            }
        };
        return new ToolCallAuthorizer(
                new ToolRegistry(List.of(new ToolDescriptor(TOOL, "view", "READ", "catalog", Set.of("low")))),
                subject -> DelegationChain.ofPrincipal(subject.id()),
                actor -> new AgentCapabilityProfile(Set.of(), Set.of(), Set.of(), ""),
                (subject, type) -> Optional.empty(),
                neverAsked,
                new ToolAuthorizationProperties());
    }

    /** A gate's binding check, whichever of the four types it is. */
    interface Binding {
        boolean isBoundTo(String toolName, ToolCallAuthorizer authorizer);
    }

    static Stream<Arguments> gatesOfEveryType() {
        ToolCallAuthorizer bound = allowing();
        return Stream.of(
                Arguments.of("ToolCallGate", bound, (Binding) ((ToolCallGate)
                        ToolCallGate.gate(new McpServerFeatures.SyncToolSpecification(tool(), (e, r) -> OK), bound)
                                .callHandler())::isBoundTo),
                Arguments.of("AsyncToolCallGate", bound, (Binding) ((AsyncToolCallGate)
                        AsyncToolCallGate.gate(new McpServerFeatures.AsyncToolSpecification(
                                tool(), (e, r) -> Mono.just(OK)), bound).callHandler())::isBoundTo),
                Arguments.of("StatelessToolCallGate", bound,
                        (Binding) ((StatelessToolCallGate) StatelessToolCallGate.gate(
                                new McpStatelessServerFeatures.SyncToolSpecification(tool(), (t, r) -> OK), bound)
                                .callHandler())::isBoundTo),
                Arguments.of("StatelessAsyncToolCallGate", bound,
                        (Binding) ((StatelessAsyncToolCallGate) StatelessAsyncToolCallGate.gate(
                                new McpStatelessServerFeatures.AsyncToolSpecification(
                                        tool(), (t, r) -> Mono.just(OK)), bound).callHandler())::isBoundTo));
    }

    @ParameterizedTest(name = "{0}") // layer-3 review L1 — bound to its own tool name AND the same authorizer instance
    @MethodSource("gatesOfEveryType")
    void aGateIsBoundOnlyToItsOwnToolNameAndItsOwnAuthorizer(String type, ToolCallAuthorizer bound, Binding gate) {
        assertThat(gate.isBoundTo(TOOL, bound)).as("own name, same authorizer").isTrue();
        assertThat(gate.isBoundTo("another_tool", bound)).as("another tool's name").isFalse();
        assertThat(gate.isBoundTo(TOOL, allowing())).as("an equal-behaving but different authorizer").isFalse();
        assertThat(gate.isBoundTo(TOOL, null)).as("no authorizer").isFalse();
    }

    @Test // U25 — every one of the four wrappers, with an empty SecurityContextHolder
    void everyWrapperDeniesUnauthenticatedWithoutInvokingTheBody() {
        SecurityContextHolder.clearContext();
        ToolCallAuthorizer authorizer = realAuthorizer();
        AtomicInteger bodyRuns = new AtomicInteger();

        CallToolResult statefulSync = ToolCallGate.gate(
                new McpServerFeatures.SyncToolSpecification(tool(), (exchange, request) -> {
                    bodyRuns.incrementAndGet();
                    return OK;
                }), authorizer).callHandler().apply(null, REQUEST);
        CallToolResult statefulAsync = AsyncToolCallGate.gate(
                new McpServerFeatures.AsyncToolSpecification(tool(), (exchange, request) -> {
                    bodyRuns.incrementAndGet();
                    return Mono.just(OK);
                }), authorizer).callHandler().apply(null, REQUEST).block();
        CallToolResult statelessSync = invokeStatelessSync(authorizer, request -> {
            bodyRuns.incrementAndGet();
            return OK;
        });
        CallToolResult statelessAsync = StatelessAsyncToolCallGate.gate(
                new McpStatelessServerFeatures.AsyncToolSpecification(tool(), (transport, request) -> {
                    bodyRuns.incrementAndGet();
                    return Mono.just(OK);
                }), authorizer).callHandler().apply(McpTransportContext.EMPTY, REQUEST).block();

        for (CallToolResult result : List.of(statefulSync, statefulAsync, statelessSync, statelessAsync)) {
            assertThat(result.isError()).isTrue();
            assertThat(metaOf(result, "code")).isEqualTo(ToolCallAuthorizer.CODE_UNAUTHENTICATED);
        }
        assertThat(bodyRuns).hasValue(0);
    }
}
