package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.security.TeamEnrichable;
import dev.dmitriikonovalov.example.usermgmt.support.AbacTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Action-enrichment IT for the user-management service (Phase 6) — QA case I7, real Postgres.
 *
 * <p>The second registry shape (the control plane) + the <strong>ungated-read degrade</strong>: the
 * {@code Team} DTO implements {@link TeamEnrichable} (the OPA-decided verb subset), but {@code getTeam} is
 * ungated (the owner-on-create bootstrap), so no gate ever write-throughs a team into the request cache.
 * The advice resolves a team's attributes <em>via the cache only</em> (never re-resolving in the read
 * path), so a {@code getTeam} response <strong>cache-misses and omits {@code _actions}</strong> — the
 * documented, correct degrade (omit, never fabricate). {@code Membership} is not enriched at all.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActionEnrichmentIT extends AbstractPostgresIT {

    private static final String SUBJECT = "it-enrich-subject";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TeamRepository teams;

    @Test // I7 — the ungated getTeam: 200, _actions ABSENT ON THE WIRE (the cache-miss degrade — never
    // fabricated). The contract is the serialized form: an absent map ⇒ the _actions KEY is omitted, never
    // an empty/all-false {} a client could misread as "no actions available". (A deserialized DTO's
    // getActions() reads back as {} because the generated field defaults to an empty map — harmless; the
    // wire is the contract, and @JsonInclude(NON_EMPTY) on Enrichable.getActions keeps {} off the wire.)
    void ungatedGetTeamOmitsActionsOnTheWire() {
        var team = teams.save(
                new Team(UUID.randomUUID(), "Enrich demo team", "catalog", UUID.randomUUID()));

        // typed read: the request still succeeds and returns the team
        var fetched = rest.exchange(
                "/api/v1/teams/{id}",
                HttpMethod.GET,
                AbacTestConfig.as(SUBJECT),
                dev.dmitriikonovalov.example.usermgmt.openapi.model.Team.class,
                team.getId());
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().getId()).isEqualTo(team.getId());

        // the wire form: NO _actions key (the omit-on-failure degrade, observable on the actual JSON)
        var raw = rest.exchange(
                "/api/v1/teams/{id}", HttpMethod.GET, AbacTestConfig.as(SUBJECT), String.class, team.getId());
        assertThat(raw.getBody())
                .as("ungated getTeam → enrichment cache-misses → _actions omitted from the wire (the degrade)")
                .doesNotContain("_actions");
    }

    @Test // I7 — the team verb set is exactly the OPA-decided subset (the affordance-honesty exclusion)
    void teamVerbSetIsTheOpaDecidedSubset() {
        // The escalation verbs are Java-co-gated (MembershipService) → excluded from affordance (ADR 0016 §8).
        TeamEnrichable marker = new TeamEnrichable() {
            @Override
            public UUID getId() {
                return null;
            }

            @Override
            public java.util.Map<String, Boolean> getActions() {
                return null;
            }

            @Override
            public void setActions(java.util.Map<String, Boolean> actions) {
                // no-op
            }
        };
        assertThat(marker.abacResourceType()).isEqualTo("team");
        assertThat(marker.abacActions())
                .containsExactly("list-members", "add-member", "remove-member")
                .doesNotContain("change-role", "define-roles", "transfer-ownership");
    }
}
