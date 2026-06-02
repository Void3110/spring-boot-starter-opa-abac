package dev.dmitriikonovalov.example.usermgmt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * User Management Service — the second example application, the ABAC <em>attribute source</em>.
 *
 * <p>Where the catalog service is the <em>resource</em> side of the demo, this service owns the
 * subject side: <b>teams</b>, <b>role definitions</b>, and <b>grants</b> (team memberships). It
 * resolves a caller's effective role for a resource and feeds it to the catalog spine's
 * {@code RoleDefinitionSupplier} (the HTTP-backed implementation that replaces the demo one).
 *
 * <p>It also <em>dogfoods</em> the starter: its own team/role management API is secured with the
 * same {@code @OpaPreAuthorize} mechanism it produces role definitions for.
 *
 * <p>Ticket 1 (this commit): a runnable, empty-but-wired scaffold mirroring the catalog app's
 * conventions. The team/role domain and the resolve API land in later tickets.
 */
@SpringBootApplication
public class UserManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserManagementApplication.class, args);
    }
}
