package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.api.UserApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.DirectoryUserList;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.UserPage;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.UserRequest;
import dev.dmitriikonovalov.opaabac.security.directory.UserDirectory;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * User read/create surface — enough to exercise persistence (ticket 2). Like the catalog app's
 * read controllers it calls the repository directly; the cross-entity transactional logic lives in
 * the {@code service/} layer introduced from ticket 3. No {@code @OpaPreAuthorize} yet — the
 * service's own ABAC is wired in ticket 4 once there is a team to authorize against.
 */
@RestController
public class UserController implements UserApi {

    private final UserRepository users;
    private final UserDirectory directory;

    public UserController(UserRepository users, UserDirectory directory) {
        this.users = users;
        this.directory = directory;
    }

    @Override
    public ResponseEntity<UserPage> listUsers(String subject, Integer page, Integer perPage) {
        // The ?subject exact-match lookup (DIRECTORY-QUERY-FILTERS): an additive branch — present
        // and non-blank narrows to a one-item page (empty on a miss, never 404, never the full
        // list); absent/blank falls through to the unchanged paged findAll.
        if (subject != null && !subject.isBlank()) {
            var match = PageDefaults.onePage(
                    users.findBySubject(subject), PageDefaults.pageRequest(page, perPage));
            return ResponseEntity.ok(UserMgmtMapper.toUserPage(match));
        }
        var result = users.findAll(PageDefaults.pageRequest(page, perPage));
        return ResponseEntity.ok(UserMgmtMapper.toUserPage(result));
    }

    @Override
    public ResponseEntity<DirectoryUserList> searchUsers(String q, Integer limit) {
        // The identity-directory search (USER-DIRECTORY-PORT): bearer-only like the sibling list —
        // finding a subject grants nothing; the acting gate stays team:add-member. The port owns every
        // fail-closed edge (blank q, outage, no match → empty), so this stays a pass-through and an
        // outage is indistinguishable from a genuine empty (the no-oracle rule) — always 200, never an
        // error. The endpoint holds no Keycloak/URL knowledge; the echoed limit is the contract clamp.
        int effective = UserDirectory.clamp(limit == null ? 0 : limit);
        return ResponseEntity.ok(
                UserMgmtMapper.toDirectoryUserList(directory.search(q, effective), effective));
    }

    @Override
    public ResponseEntity<dev.dmitriikonovalov.example.usermgmt.openapi.model.User> createUser(
            UserRequest request) {
        // bootstrap: pre-membership, authenticated-only by design — a new user has no team membership to
        // authorize against yet, so this endpoint is deliberately ungated (no @OpaPreAuthorize).
        var entity = new User(UUID.randomUUID(), request.getSubject(), request.getDisplayName());
        var dto = UserMgmtMapper.toDto(users.save(entity));
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(dto.getId())
                .toUri();
        return ResponseEntity.created(location).body(dto);
    }

    @Override
    public ResponseEntity<dev.dmitriikonovalov.example.usermgmt.openapi.model.User> getUser(
            UUID userId) {
        var entity = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        return ResponseEntity.ok(UserMgmtMapper.toDto(entity));
    }
}
