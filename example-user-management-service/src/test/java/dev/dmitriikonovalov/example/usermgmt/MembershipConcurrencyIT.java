package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dmitriikonovalov.example.usermgmt.domain.SystemRoles;
import dev.dmitriikonovalov.example.usermgmt.domain.Team;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembership;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamMembershipRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.service.MembershipService;
import dev.dmitriikonovalov.example.usermgmt.service.MembershipView;
import dev.dmitriikonovalov.example.usermgmt.service.SubsetRuleViolationException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The latch-based concurrency IT for team-scoped grant mutations (guide Rule 6; retro-audit
 * 2026-06-12 TOCTOU follow-up): the subset/ceiling decision must be made on actor state read
 * <strong>under the team-row lock that holds through the commit</strong> (Rule 1). Without the lock,
 * a demotion of the actor could land between the check and the grant, letting an ex-administrator
 * confer a role they no longer hold.
 *
 * <p>Deterministic, no sleeps-as-synchronization: the demotion transaction is held open on a latch
 * (its team-row lock with it), the racing grant is proven <em>blocked</em>, and only after the
 * demotion commits does the grant proceed — against the actor's NEW role, and fails the guard.
 */
class MembershipConcurrencyIT extends AbstractSecuredPostgresIT {

    @Autowired private MembershipService membershipService;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private TeamMembershipRepository memberships;
    @Autowired private PlatformTransactionManager txManager;

    private User user(String name) {
        return users.save(new User(UUID.randomUUID(), "sub-" + name + "-" + UUID.randomUUID(), name));
    }

    @Test
    void demotedActorCannotWinTheGrantRace() throws Exception {
        Team team = teams.save(new Team(UUID.randomUUID(), "Race", "catalog", UUID.randomUUID()));
        User owner = user("owner");
        User admin = user("admin");
        User target = user("target");
        memberships.save(new TeamMembership(
                UUID.randomUUID(), team.getId(), owner.getId(), SystemRoles.OWNER_ID));
        memberships.save(new TeamMembership(
                UUID.randomUUID(), team.getId(), admin.getId(), SystemRoles.ADMINISTRATOR_ID));

        CountDownLatch demoted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // T1 (the owner): demote the admin to member, then HOLD the transaction open — the
            // team-row lock is held with it.
            Future<?> demotion = pool.submit(() -> new TransactionTemplate(txManager)
                    .executeWithoutResult(status -> {
                        membershipService.changeRole(
                                owner.getId(), team.getId(), admin.getId(), SystemRoles.MEMBER);
                        demoted.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("release latch timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }));
            assertThat(demoted.await(10, TimeUnit.SECONDS)).isTrue();

            // T2 (the still-uncommitted-demoted admin): grant administrator to the target. Without the
            // team lock this would read the actor's OLD role and commit the grant; with it, it BLOCKS.
            Future<MembershipView> grant = pool.submit(() -> membershipService.addMember(
                    admin.getId(), team.getId(), target.getId(), SystemRoles.ADMINISTRATOR));
            assertThatThrownBy(() -> grant.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            demotion.get(10, TimeUnit.SECONDS);

            // The grant proceeds only after the demotion committed — and is decided on the NEW state.
            assertThatThrownBy(() -> grant.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SubsetRuleViolationException.class);
            assertThat(memberships.findByTeamIdAndUserId(team.getId(), target.getId())).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
