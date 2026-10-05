package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * ENGINE-ERRORS U11 — the shape of the "could not decide" family (ADR 0037 §2) and the
 * {@link PolicyEngineException} invariants its consumers rely on.
 */
class PolicyEngineExceptionTest {

    private static final Throwable CAUSE = new java.io.IOException("connection reset");

    @Test
    void everyFactoryProducesItsKind_andHttpStatusIsPresentOnlyForHttpStatus() {
        List<PolicyEngineException> all = List.of(
                PolicyEngineException.transport("t", CAUSE),
                PolicyEngineException.timeout("t", CAUSE),
                PolicyEngineException.httpStatus(503, "t"),
                PolicyEngineException.interrupted("t", CAUSE),
                PolicyEngineException.malformedResponse("t", null),
                PolicyEngineException.undefinedDecision("t"),
                PolicyEngineException.evaluationError("t"),
                PolicyEngineException.circuitOpen("t", null));

        Set<PolicyEngineException.Kind> kinds =
                all.stream().map(PolicyEngineException::kind).collect(Collectors.toSet());
        assertThat(kinds).isEqualTo(EnumSet.allOf(PolicyEngineException.Kind.class));
        for (PolicyEngineException e : all) {
            if (e.kind() == PolicyEngineException.Kind.HTTP_STATUS) {
                assertThat(e.httpStatus()).hasValue(503);
            } else {
                assertThat(e.httpStatus()).as("%s", e.kind()).isEmpty();
            }
            assertThat(e.getMessage()).startsWith(e.kind().name());
        }
        assertThat(PolicyEngineException.transport("t", CAUSE)).hasCause(CAUSE);
    }

    @Test
    void bothShippedMembersAreInTheFamily_andStayUnchecked() {
        DecisionIndeterminateException engine = PolicyEngineException.undefinedDecision("no package");
        DecisionIndeterminateException role = new RoleResolutionException("role source down");

        assertThat(engine).isInstanceOf(RuntimeException.class);
        assertThat(role).isInstanceOf(RuntimeException.class);
    }

    @Test
    void anExistingRoleResolutionCatchStillCatches() {
        Throwable caught = null;
        try {
            throw new RoleResolutionException("role source down", CAUSE);
        } catch (RoleResolutionException e) {
            caught = e;
        }
        assertThat(caught).isInstanceOf(DecisionIndeterminateException.class).hasCause(CAUSE);
    }

    @Test
    void theBaseIsAbstractWithProtectedConstructors_soAnSpiCanOptIn() {
        assertThat(Modifier.isAbstract(DecisionIndeterminateException.class.getModifiers())).isTrue();
        Constructor<?>[] constructors = DecisionIndeterminateException.class.getDeclaredConstructors();
        assertThat(constructors).isNotEmpty();
        assertThat(Arrays.stream(constructors).allMatch(c -> Modifier.isProtected(c.getModifiers()))).isTrue();

        DecisionIndeterminateException optedIn = new DecisionIndeterminateException("adopter outage") {};
        assertThat(optedIn).hasMessage("adopter outage");
    }
}
