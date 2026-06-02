package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.openapi.api.MetaApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.Pong;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * A liveness ping — the only endpoint in the ticket-1 scaffold, present so OpenAPI codegen has a
 * route to generate and the wired Spring context has something real to serve. Replaced/joined by the
 * team / role-definition / membership / effective-role endpoints in later tickets.
 */
@RestController
public class PingController implements MetaApi {

    @Override
    public ResponseEntity<Pong> ping() {
        return ResponseEntity.ok(new Pong().service("user-management-service").status("UP"));
    }
}
