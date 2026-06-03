package dev.dmitriikonovalov.example.usermgmt.domain;

import java.util.List;
import java.util.UUID;

/**
 * The four immutable system role codes and their seeded UUIDs. These are seeded by Liquibase
 * (changeset {@code 0002}) with the <em>same</em> stable ids/codes declared here, so application
 * code can resolve them without a query when bootstrapping (e.g. owner-on-create in ticket 3).
 *
 * <p>The codes are stable contract: policies, tests, and the resolve API rely on them. The
 * permission sets mirror the system-role table in {@code 00-DESIGN.md} and the seed in the changelog.
 */
public final class SystemRoles {

    public static final String OWNER = "owner";
    public static final String ADMINISTRATOR = "administrator";
    public static final String MEMBER = "member";
    public static final String VIEWER = "viewer";

    /** Stable seed ids (must match the Liquibase data changeset exactly). */
    public static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID ADMINISTRATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    public static final List<String> ALL_CODES = List.of(OWNER, ADMINISTRATOR, MEMBER, VIEWER);

    private SystemRoles() {
    }
}
