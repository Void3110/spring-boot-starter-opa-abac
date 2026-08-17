package dev.dmitriikonovalov.example.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dmitriikonovalov.example.catalog.openapi.model.Catalog;
import dev.dmitriikonovalov.example.catalog.openapi.model.CatalogPage;
import dev.dmitriikonovalov.example.catalog.security.CatalogProvenanceMemo;
import dev.dmitriikonovalov.example.catalog.web.CatalogProvenanceAdvice;
import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.core.RoleDefinition;
import dev.dmitriikonovalov.opaabac.core.RoleDefinitionSupplier;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.security.AbacAuthentication;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Unit tests for {@link CatalogProvenanceAdvice} — QA <b>U7</b> (the list derivation reads the memo)
 * and <b>U8</b> (the GET derivation reads the role stamp), ADR 0033.
 *
 * <p>The assertions that matter most are the <em>absence</em> ones, and they are made on the
 * <b>serialized bytes</b>, not on the getter: an absent {@code _provenance} must mean <em>unknown</em>,
 * and a field that serializes as {@code "_provenance": null} would let a client read "not supervised"
 * out of a value the server never computed. Same assertion style as {@code MultiRootEnrichmentIT}'s
 * {@code has("_actions") == false}.
 */
class CatalogProvenanceAdviceTest {

    private static final UUID C1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID C2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final RoleDefinition MEMBERSHIP_ROLE = new RoleDefinition(
            "owner", Map.of("provenance", "membership"), Map.of("catalog", List.of("READ", "WRITE")));
    private static final RoleDefinition SUPERVISOR_ROLE = new RoleDefinition(
            "supervisor-readonly", Map.of("provenance", "supervised"), Map.of("catalog", List.of("READ")));
    /** A deployment whose role source does not stamp at all — the value is unknown, not "member". */
    private static final RoleDefinition UNSTAMPED_ROLE =
            new RoleDefinition("owner", Map.of(), Map.of("catalog", List.of("READ")));

    private final RoleDefinitionSupplier supplier = mock(RoleDefinitionSupplier.class);
    private final CatalogProvenanceAdvice advice = new CatalogProvenanceAdvice(supplier);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        AbacContext.Subject subject = new AbacContext.Subject("sub-1", List.of("catalog-viewer"), Map.of());
        SecurityContextHolder.getContext()
                .setAuthentication(new AbacAuthentication(subject));
    }

    private static Catalog catalog(UUID id) {
        return new Catalog().id(id).name("catalog " + id);
    }

    private static CatalogPage pageOf(Catalog... items) {
        return new CatalogPage().count((long) items.length).page(1).perPage(20).items(List.of(items));
    }

    private Object write(Object body, HttpMethod method) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method.name(), "/api/v1/catalogs");
        ServerHttpRequest request =
                new org.springframework.http.server.ServletServerHttpRequest(servletRequest);
        return advice.beforeBodyWrite(body, null, null, null, request, null);
    }

    /** Serialize and report whether the key is on the wire at all (not whether it is null). */
    private boolean wireHasProvenance(Catalog catalog) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(catalog)).has("_provenance");
    }

    // ---- U7: the list derivation --------------------------------------------------------------

    @Test
    void u7_listStampsSupervisedFromTheMemoAndMemberOtherwise() {
        CatalogProvenanceMemo.write(List.of(C2));
        CatalogPage page = pageOf(catalog(C1), catalog(C2), catalog(C3));

        write(page, HttpMethod.GET);

        assertThat(page.getItems().get(0).getProvenance()).isEqualTo("member");
        assertThat(page.getItems().get(1).getProvenance()).isEqualTo("supervised");
        assertThat(page.getItems().get(2).getProvenance()).isEqualTo("member");
    }

    @Test
    void u7_aPresentButEmptyMemoLabelsEveryRowMember() {
        // Present-but-empty is a real answer — "this page has no supervised leg" — and is NOT absence.
        // A plain member's page, an agent-marked call and a supervised-source outage all land here.
        CatalogProvenanceMemo.write(List.of());
        CatalogPage page = pageOf(catalog(C1), catalog(C2));

        write(page, HttpMethod.GET);

        assertThat(page.getItems()).allSatisfy(item ->
                assertThat(item.getProvenance()).isEqualTo("member"));
    }

    @Test
    void u7_noMemoOmitsTheFieldEntirely() throws Exception {
        // A body that never passed through the two-leg authorizer's query path. The server did not
        // compute the label, so the key must not reach the wire at all.
        CatalogPage page = pageOf(catalog(C1), catalog(C2));

        write(page, HttpMethod.GET);

        for (Catalog item : page.getItems()) {
            assertThat(item.getProvenance()).isNull();
            assertThat(wireHasProvenance(item))
                    .as("_provenance must be ABSENT, not null, when it was never computed")
                    .isFalse();
        }
    }

    @Test
    void u7_noMemoOmitsProvenanceButLeavesTheREST_ofTheEnrichmentIntact() throws Exception {
        // QA I2's last sub-case: the memo-less page must lose ONLY _provenance. The advice runs on
        // every catalog body, so a version that rebuilt or replaced items instead of stamping them
        // would silently drop _actions -- an affordance the console renders its buttons from -- and
        // u7_noMemoOmitsTheFieldEntirely above would still pass, because it only asserts the absence.
        Map<String, Boolean> affordances = Map.of("view", true, "update", false);
        CatalogPage page = pageOf(catalog(C1).actions(affordances), catalog(C2).actions(affordances));

        // Assert on what the advice RETURNED, not on the input we still hold a reference to.
        // Asserting the input is the trap: `beforeBodyWrite` is a ResponseBodyAdvice, so replacing
        // the body by returning a new object is the idiomatic implementation — and it would leave
        // our `page` untouched, passing every assertion below while dropping _actions on the wire.
        Object returned = write(page, HttpMethod.GET);

        assertThat(returned)
                .as("the advice must stamp in place, not hand back a rebuilt body")
                .isSameAs(page);
        for (Catalog item : ((CatalogPage) returned).getItems()) {
            assertThat(wireHasProvenance(item))
                    .as("_provenance must be ABSENT when it was never computed")
                    .isFalse();
            assertThat(item.getActions())
                    .as("_actions must survive the advice untouched")
                    .isEqualTo(affordances);
        }
        verifyNoRoleLookup();
    }

    @Test
    void u7_aBodyThatIsNotACatalogShapeIsUntouched() {
        CatalogProvenanceMemo.write(List.of(C1));
        Object body = Map.of("some", "other body");

        Object returned = write(body, HttpMethod.GET);

        assertThat(returned).isSameAs(body);
        verifyNoRoleLookup();
    }

    // ---- U8: the GET derivation ---------------------------------------------------------------

    @Test
    void u8_getMapsTheSupervisedStamp() {
        authenticate();
        when(supplier.lookup("sub-1", "catalog", C1.toString())).thenReturn(Optional.of(SUPERVISOR_ROLE));
        Catalog body = catalog(C1);

        write(body, HttpMethod.GET);

        assertThat(body.getProvenance()).isEqualTo("supervised");
    }

    @Test
    void u8_getMapsTheMembershipStamp() {
        authenticate();
        when(supplier.lookup("sub-1", "catalog", C1.toString())).thenReturn(Optional.of(MEMBERSHIP_ROLE));
        Catalog body = catalog(C1);

        write(body, HttpMethod.GET);

        assertThat(body.getProvenance()).isEqualTo("member");
    }

    @Test
    void u8_everyUnknownStateOmits() throws Exception {
        authenticate();

        // (a) the role resolves but carries no provenance stamp
        when(supplier.lookup("sub-1", "catalog", C1.toString())).thenReturn(Optional.of(UNSTAMPED_ROLE));
        Catalog unstamped = catalog(C1);
        write(unstamped, HttpMethod.GET);
        assertThat(wireHasProvenance(unstamped)).isFalse();

        // (b) a stamp outside the vocabulary — omit rather than pass an unknown value through
        when(supplier.lookup("sub-1", "catalog", C2.toString())).thenReturn(Optional.of(
                new RoleDefinition("x", Map.of("provenance", "delegated"), Map.of())));
        Catalog unknownValue = catalog(C2);
        write(unknownValue, HttpMethod.GET);
        assertThat(wireHasProvenance(unknownValue)).isFalse();

        // (c) no role at all
        when(supplier.lookup("sub-1", "catalog", C3.toString())).thenReturn(Optional.empty());
        Catalog noRole = catalog(C3);
        write(noRole, HttpMethod.GET);
        assertThat(wireHasProvenance(noRole)).isFalse();
    }

    @Test
    void u8_aRoleSourceOutageIsSwallowedAndTheBodyIsOtherwiseIntact() throws Exception {
        authenticate();
        when(supplier.lookup(anyString(), anyString(), anyString()))
                .thenThrow(new RoleResolutionException("role source down"));
        Catalog body = catalog(C1);

        Object returned = write(body, HttpMethod.GET);

        assertThat(returned).isSameAs(body);
        assertThat(wireHasProvenance(body)).isFalse();
        // The rest of the body survives — the label is decoration on an already-allowed read.
        assertThat(body.getId()).isEqualTo(C1);
        assertThat(body.getName()).isEqualTo("catalog " + C1);
    }

    @Test
    void u8_withNoSubjectNothingIsLookedUpAndTheFieldIsAbsent() throws Exception {
        // No authenticated ABAC subject → there is nobody to resolve a role for.
        Catalog body = catalog(C1);

        write(body, HttpMethod.GET);

        assertThat(wireHasProvenance(body)).isFalse();
        verifyNoRoleLookup();
    }

    @Test
    void u8_createAndUpdateBodiesAreUntouchedAndCostNoLookup() throws Exception {
        // createCatalog and updateCatalog also return a bare Catalog. create's gate is type-level (a
        // null resource id), so a lookup by the brand-new id would be a guaranteed memo MISS — a real
        // user-service round trip on every create. Both are excluded by the HTTP verb.
        authenticate();

        Catalog created = catalog(C1);
        write(created, HttpMethod.POST);
        assertThat(wireHasProvenance(created)).isFalse();

        Catalog updated = catalog(C2);
        write(updated, HttpMethod.PUT);
        assertThat(wireHasProvenance(updated)).isFalse();

        verifyNoRoleLookup();
    }

    private void verifyNoRoleLookup() {
        verify(supplier, never()).lookup(anyString(), anyString(), anyString());
    }
}
