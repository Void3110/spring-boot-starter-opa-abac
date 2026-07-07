package dev.dmitriikonovalov.example.usermgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.opaabac.security.directory.DirectoryUser;
import dev.dmitriikonovalov.opaabac.security.directory.NoOpUserDirectory;
import dev.dmitriikonovalov.opaabac.security.directory.UserDirectory;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * I4a/I4b (USER-DIRECTORY-PORT) — the identity-directory search endpoint at the wire: rows serialize
 * to the bounded plain list carrying <em>only</em> {@code subject}+{@code displayName} (the disclosure
 * ceiling at the HTTP boundary), and the no-directory default is a <strong>200 with empty items</strong>
 * — never 404, never 500 (the no-oracle empty). Standalone MockMvc over a stubbed port — the JSON
 * contract, not persistence (the directory has none).
 */
class UserControllerSearchTest {

    private final UserRepository users = mock(UserRepository.class);

    private MockMvc mvcWith(UserDirectory directory) {
        return MockMvcBuilders.standaloneSetup(new UserController(users, directory)).build();
    }

    @Test // I4a — two stub rows -> 200 {items:[…2…], limit:10}, each row exactly subject+displayName
    void searchSerializesTheBoundedPlainList() throws Exception {
        UserDirectory stub = (q, limit) -> List.of(
                new DirectoryUser("sub-1", "alice"),
                new DirectoryUser("sub-2", "albert"));

        mvcWith(stub).perform(get("/api/v1/users/search").param("q", "al").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].subject").value("sub-1"))
                .andExpect(jsonPath("$.items[0].displayName").value("alice"))
                .andExpect(jsonPath("$.items[1].subject").value("sub-2"))
                // the disclosure ceiling at the wire: exactly two fields per row, nothing wider
                .andExpect(jsonPath("$.items[0].*", hasSize(2)))
                .andExpect(jsonPath("$.items[1].*", hasSize(2)))
                // a plain bounded list, not the page envelope
                .andExpect(jsonPath("$.count").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test // I4b — the NoOp default -> 200 with empty items (not 404, not 500); absent limit echoes 20
    void noOpDirectoryIsATwoHundredEmpty() throws Exception {
        mvcWith(new NoOpUserDirectory()).perform(get("/api/v1/users/search").param("q", "x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.limit").value(UserDirectory.DEFAULT_LIMIT));
    }

    @Test // the echoed limit is the effective (clamped) one, and the port receives the same value
    void limitEchoAndPortCallUseTheContractClamp() throws Exception {
        AtomicInteger portSaw = new AtomicInteger();
        UserDirectory stub = (q, limit) -> {
            portSaw.set(limit);
            return List.of();
        };

        mvcWith(stub).perform(get("/api/v1/users/search").param("q", "a").param("limit", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(UserDirectory.MAX_LIMIT));

        assertThat(portSaw.get()).isEqualTo(UserDirectory.MAX_LIMIT);
    }
}
