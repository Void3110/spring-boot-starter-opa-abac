package dev.dmitriikonovalov.example.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * T2 unit cases <b>U14</b> (the synthesized role's shape) and <b>U16</b> (it round-trips through the
 * core record as {@code input.role_definition}, marker intact, with no envelope field added).
 *
 * <p><b>Scope, per the QA case.</b> U14 owns the role SHAPE. The complementary
 * "contents are closed" half is <b>U35–U40, owned by T3</b>: it cannot hold before the confinement
 * rule lands, and an ancestor-less policy probe would pass for the wrong reason — precisely how a live
 * fail-open was green-lit (ADR 0031 §Context). The {@code data.catalog.filter} half of U14 is asserted
 * against the real corpus with {@code opa eval} and recorded verbatim in {@code STATUS-02.md}, since
 * T2 may not edit Rego.
 */
class SupervisorRolesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test // U14 — exactly the coarse token on the supervised type, and NOTHING else
    void synthesizedRoleGrantsOnlyTheCoarseReadTokenOnTheSupervisedType() {
        RoleDefinition role = SupervisorRoles.readOnlyFor("catalog");

        assertThat(role.permissions()).isEqualTo(Map.of("catalog", List.of("READ")));
        // No child-type key and no wildcard — the wildcard would expand to the requested type and
        // reopen exactly what this slice closes.
        assertThat(role.permissions()).doesNotContainKeys("category", "product", "*");
        // The COARSE token, never a fine verb: a fine verb expands to the empty set and grants nothing.
        assertThat(role.permissions().get("catalog")).doesNotContain("view", "list", "list-members");
        assertThat(role.requiredTags()).isEmpty();
        assertThat(role.matchMode()).isNull();
        assertThat(role.deniedActions()).isEmpty();
        assertThat(role.code()).isEqualTo(SupervisorRoles.SUPERVISOR_CODE);
        assertThat(role.attributes())
                .containsEntry(SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_SUPERVISED);
    }

    @Test // the role names the type actually supervised — never a hard-coded one
    void synthesizedRoleNamesTheSupervisedTypeOnly() {
        assertThat(SupervisorRoles.readOnlyFor("product").permissions())
                .isEqualTo(Map.of("product", List.of("READ")));
    }

    @Test // U16 — round-trips as input.role_definition; the marker survives; NO envelope field added
    void roleRoundTripsThroughTheCoreRecordWithTheMarkerIntact() {
        RoleDefinition role = SupervisorRoles.readOnlyFor("catalog");

        String json = MAPPER.writeValueAsString(role);
        RoleDefinition back = MAPPER.readValue(json, RoleDefinition.class);

        assertThat(back).isEqualTo(role);
        assertThat(back.attributes())
                .containsEntry(SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_SUPERVISED);

        // The wire carries exactly the three always-present core fields — the optional denial/tag
        // fields stay omitted (NON_EMPTY/NON_NULL), so this is byte-shape-identical to any other
        // untagged, denial-free role. No field was added to the envelope.
        var node = MAPPER.readTree(json);
        assertThat(node.propertyNames()).containsExactlyInAnyOrder("code", "attributes", "permissions");
        assertThat(node.get("attributes").get("provenance").asString()).isEqualTo("supervised");
    }
}
