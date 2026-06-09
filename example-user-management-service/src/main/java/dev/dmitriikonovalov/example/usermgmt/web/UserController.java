package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.domain.User;
import dev.dmitriikonovalov.example.usermgmt.domain.UserRepository;
import dev.dmitriikonovalov.example.usermgmt.openapi.api.UserApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.UserRequest;
import java.util.List;
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

    public UserController(UserRepository users) {
        this.users = users;
    }

    @Override
    public ResponseEntity<List<dev.dmitriikonovalov.example.usermgmt.openapi.model.User>> listUsers() {
        var result = users.findAll().stream().map(UserMgmtMapper::toDto).toList();
        return ResponseEntity.ok(result);
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
