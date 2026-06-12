package dev.dmitriikonovalov.example.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dmitriikonovalov.opaabac.core.AbacContext;
import dev.dmitriikonovalov.opaabac.security.AbacSubjectExtractor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the security chain's actuator posture (retro-audit 2026-06-12): only {@code /actuator/health}
 * is anonymous; the broad demo actuator surface ({@code env}, {@code beans}, …) requires an
 * authenticated subject — the old {@code permitAll} catch-all exposed it unauthenticated.
 *
 * <p>Overrides the base's always-authenticated permissive extractor with a header-driven one
 * ({@code @Primary}), so the anonymous case is actually anonymous.
 */
@AutoConfigureMockMvc
@Import(ActuatorSecurityIT.HeaderSubjectConfig.class)
class ActuatorSecurityIT extends AbstractPostgresIT {

    private static final String SUBJECT_HEADER = "X-Test-Subject";

    @Autowired
    private MockMvc mockMvc;

    @Test // health stays open — the container/gateway probe needs no token
    void health_isAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test // env (and the rest of the broad demo surface) is NOT readable anonymously
    void env_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isForbidden());
    }

    @Test // an authenticated subject (any) may read the demo actuator surface
    void env_readableWithASubject() throws Exception {
        mockMvc.perform(get("/actuator/env").header(SUBJECT_HEADER, "it-operator"))
                .andExpect(status().isOk());
    }

    @TestConfiguration
    static class HeaderSubjectConfig {
        @Bean
        @Primary
        AbacSubjectExtractor headerSubjectExtractor() {
            return request -> {
                String subject = request.getHeader(SUBJECT_HEADER);
                return subject == null || subject.isBlank()
                        ? Optional.empty()
                        : Optional.of(new AbacContext.Subject(subject, List.of(), Map.of()));
            };
        }
    }
}
