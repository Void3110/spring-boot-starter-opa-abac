package dev.dmitriikonovalov.example.usermgmt.domain;

import java.util.List;
import java.util.UUID;

/**
 * The five immutable system role codes and their seeded UUIDs. These are seeded by Liquibase
 * (changeset {@code 0002}, migrated by {@code 0006}) with the <em>same</em> stable ids/codes declared
 * here, so application code can resolve them without a query when bootstrapping (e.g. owner-on-create
 * in ticket 3).
 *
 * <p>The codes are stable contract: policies, tests, and the resolve API rely on them. The five-tier
 * ladder (ADR 0007, Phase 6.5): {@code reader 10} · {@code member 20} · {@code senior 25} ·
 * {@code administrator 30} · {@code owner 40}. Changeset {@code 0006} migrated the permission sets to
 * coarse categories, inserted {@code senior}, and renamed {@code viewer} to {@code reader} — same id,
 * so membership FKs were untouched.
 */
public final class SystemRoles {

    public static final String OWNER = "owner";
    public static final String ADMINISTRATOR = "administrator";
    public static final String SENIOR = "senior";
    public static final String MEMBER = "member";
    public static final String READER = "reader";

    /** Stable seed ids (must match the Liquibase data changesets exactly). */
    public static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID ADMINISTRATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID READER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    public static final UUID SENIOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");

    public static final List<String> ALL_CODES = List.of(OWNER, ADMINISTRATOR, SENIOR, MEMBER, READER);

    private SystemRoles() {
    }
}
