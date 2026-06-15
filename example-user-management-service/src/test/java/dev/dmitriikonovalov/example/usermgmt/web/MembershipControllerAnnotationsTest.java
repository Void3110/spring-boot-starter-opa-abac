package dev.dmitriikonovalov.example.usermgmt.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * U5 (Phase 6.7) — the membership endpoints gate on their own <em>fine</em> verbs, not the retired
 * coarse {@code team:manage}. Reflection assert (no Spring context): each handler carries the expected
 * {@code @OpaPreAuthorize(action=...)}, {@code team:manage} appears nowhere, and the resource binding
 * ({@code resourceType="'team'"}, {@code resourceId="#teamId"}) is unchanged.
 */
class MembershipControllerAnnotationsTest {

    private OpaPreAuthorize gateOn(String methodName) {
        Method method = Arrays.stream(MembershipController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .filter(m -> m.isAnnotationPresent(OpaPreAuthorize.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no @OpaPreAuthorize on " + methodName));
        return method.getAnnotation(OpaPreAuthorize.class);
    }

    @Test
    void listMembersGatesOnReadVerb() {
        assertThat(gateOn("listMembers").action()).isEqualTo("team:list-members");
    }

    @Test
    void addMemberGatesOnControlVerb() {
        assertThat(gateOn("addMember").action()).isEqualTo("team:add-member");
    }

    @Test
    void changeMemberRoleGatesOnControlVerb() {
        assertThat(gateOn("changeMemberRole").action()).isEqualTo("team:change-role");
    }

    @Test
    void removeMemberGatesOnControlVerb() {
        assertThat(gateOn("removeMember").action()).isEqualTo("team:remove-member");
    }

    @Test
    void noEndpointStillUsesTheRetiredCoarseManageVerb() {
        for (Method m : MembershipController.class.getDeclaredMethods()) {
            OpaPreAuthorize gate = m.getAnnotation(OpaPreAuthorize.class);
            if (gate != null) {
                assertThat(gate.action()).as("%s action", m.getName()).isNotEqualTo("team:manage");
            }
        }
    }

    @Test
    void resourceBindingUnchangedOnEveryGate() {
        for (String method : new String[] {"listMembers", "addMember", "changeMemberRole", "removeMember"}) {
            OpaPreAuthorize gate = gateOn(method);
            assertThat(gate.resourceType()).as("%s resourceType", method).isEqualTo("'team'");
            assertThat(gate.resourceId()).as("%s resourceId", method).isEqualTo("#teamId");
        }
    }
}
