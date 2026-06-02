package dev.dmitriikonovalov.example.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Ticket-1 smoke test: the wired Spring context boots against a real Postgres (Testcontainers),
 * Liquibase runs the (currently empty) changelog, and {@code ddl-auto: validate} passes. Proves the
 * scaffold is genuinely runnable before any domain lands in ticket 2.
 */
class ContextLoadsIT extends AbstractPostgresIT {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoadsWithRealPostgres() {
        assertThat(dataSource).isNotNull();
    }
}
