package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RoleResolutionException} (QA case U1) — the role-source outage signal that the
 * tri-state {@link RoleDefinitionSupplier} contract throws so consumers can fail closed instead of
 * mistaking an outage for an authoritative no-role.
 */
class RoleResolutionExceptionTest {

    @Test // U1 — the message-only constructor
    void carriesMessage() {
        RoleResolutionException ex = new RoleResolutionException("source unavailable");
        assertThat(ex.getMessage()).isEqualTo("source unavailable");
        assertThat(ex.getCause()).isNull();
    }

    @Test // U1 — the message + cause constructor (the wrapped cause is for logs only)
    void carriesMessageAndCause() {
        Throwable cause = new java.io.IOException("connection refused");
        RoleResolutionException ex = new RoleResolutionException("source unavailable", cause);
        assertThat(ex.getMessage()).isEqualTo("source unavailable");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test // U1 — unchecked: keeps the @FunctionalInterface lambda ergonomics intact
    void isUnchecked() {
        assertThat(new RoleResolutionException("m")).isInstanceOf(RuntimeException.class);
    }
}
