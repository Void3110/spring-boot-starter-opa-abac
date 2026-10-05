package dev.dmitriikonovalov.opaabac.autoconfigure;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dmitriikonovalov.opaabac.core.PolicyEngineException;
import dev.dmitriikonovalov.opaabac.core.RoleResolutionException;
import dev.dmitriikonovalov.opaabac.security.AuthorizationIndeterminateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * ENGINE-ERRORS I5 — the fallback advice wins over an application's own {@code AccessDeniedException}
 * handler, through Spring MVC's real exception resolution.
 *
 * <p>Spring MVC asks advices in order and the first with a matching handler wins. The application's advice
 * below handles {@code AccessDeniedException} — a supertype of the gate's {@link AuthorizationIndeterminateException}
 * — and is registered <em>first</em>, so without the fallback's {@code @Order(HIGHEST_PRECEDENCE)} an outage
 * would come back as that advice's 403. A plain denial still reaches the application's 403.
 */
class IndeterminateDecisionProblemAdviceTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new AppDenialAdvice(), new IndeterminateDecisionProblemAdvice())
                .build();
    }

    @Test // the gate's type is claimed by the fallback ahead of the app's AccessDeniedException handler
    void gateIndeterminate_answers503_notTheAppsDenial() throws Exception {
        mockMvc.perform(get("/gate"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(IndeterminateDecisionProblemAdvice.DETAIL))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test // a list query's raw core family member — no gate in front of it — answers 503, not 500
    void rawIndeterminate_answers503() throws Exception {
        mockMvc.perform(get("/list"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test // the control: a plain denial still reaches the application's own 403 handler
    void plainDenial_staysTheAppsDenial() throws Exception {
        mockMvc.perform(get("/denied"))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/gate")
        String gate() {
            throw new AuthorizationIndeterminateException(
                    "pre-authorize decision indeterminate", new RoleResolutionException("role source down"));
        }

        @GetMapping("/list")
        String list() {
            throw PolicyEngineException.timeout("compile for path 'category'", null);
        }

        @GetMapping("/denied")
        String denied() {
            throw new AccessDeniedException("no");
        }
    }

    /** An application's own advice that does NOT extend the base — it maps every denial to a bare 403. */
    @RestControllerAdvice
    static class AppDenialAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<String> denied(AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("denied");
        }
    }
}
