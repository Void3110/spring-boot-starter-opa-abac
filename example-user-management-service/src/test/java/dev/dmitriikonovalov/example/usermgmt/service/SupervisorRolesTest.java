package dev.dmitriikonovalov.example.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * SUPERVISED-SCOPE unit cases <b>U14</b> (the synthesized role's shape) and <b>U16</b> (it round-trips
 * through the core record as {@code input.role_definition}, marker intact, with no envelope field
 * added) — <b>U14 rewritten by PRODUCTION-TIER U5</b> (see below).
 *
 * <p><b>Scope, per the QA case.</b> U14 owns the role SHAPE. The complementary
 * "contents are closed" half is <b>U35–U40</b>: it cannot hold before the confinement rule lands, and
 * an ancestor-less policy probe would pass for the wrong reason — precisely how a live fail-open was
 * green-lit (ADR 0031 §Context).
 *
 * <p><b>The PRODUCTION-TIER rewrite (U5), deliberate and planned.</b> Slice A asserted the role names
 * <em>no</em> child-type key, because A closed contents outright. Slice B opens <b>non-production</b>
 * contents by widening the role to name {@code category} and {@code product} <b>directly</b> — which is
 * exactly the future A's own comment anticipated ("a later slice that widens the synthesized role by
 * naming child types needs no policy change"). What did <em>not</em> change, and is still asserted
 * here: no {@code "*"} key, coarse tokens only, READ-only, the provenance marker, and the untouched
 * envelope. How far the widened read reaches is decided by the tier clauses in
 * {@code category.rego}/{@code product.rego}, not by this role.
 */
class SupervisorRolesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test // U5 — the catalog role grants coarse READ on the catalog AND its two child types
    void catalogRoleGrantsCoarseReadOnTheCatalogAndItsChildren() {
        RoleDefinition role = SupervisorRoles.readOnlyFor("catalog");

        assertThat(role.permissions()).isEqualTo(Map.of(
                "catalog", List.of("READ"),
                "category", List.of("READ"),
                "product", List.of("READ")));
        // Still no wildcard: "*" expands to whatever type is requested, which would reach types the
        // supervised relation says nothing about.
        assertThat(role.permissions()).doesNotContainKey("*");
        // Every key carries EXACTLY the coarse READ token — which pins both halves at once: never a fine
        // verb (those expand to the empty set and grant nothing), and never WRITE/TAG/GRANT/CONTROL, so
        // A's read-only ceiling cells (PUT/DELETE 403) are unaffected by the widening. hasSize pins the
        // subject non-empty first: allSatisfy over an empty collection asserts nothing at all.
        assertThat(role.permissions().values())
                .hasSize(3)
                .allSatisfy(tokens -> assertThat(tokens).containsExactly("READ"));
        assertThat(role.requiredTags()).isEmpty();
        assertThat(role.matchMode()).isNull();
        assertThat(role.deniedActions()).isEmpty();
        assertThat(role.code()).isEqualTo(SupervisorRoles.SUPERVISOR_CODE);
        assertThat(role.attributes())
                .containsEntry(SupervisorRoles.PROVENANCE_ATTRIBUTE, SupervisorRoles.PROVENANCE_SUPERVISED);
    }

    @Test // U5 — any OTHER supervised type keeps the single-key shape, unchanged from slice A
    void anyOtherSupervisedTypeKeepsTheSingleKeyShape() {
        assertThat(SupervisorRoles.readOnlyFor("product").permissions())
                .isEqualTo(Map.of("product", List.of("READ")));
        assertThat(SupervisorRoles.readOnlyFor("team").permissions())
                .isEqualTo(Map.of("team", List.of("READ")));
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
