package dev.dmitriikonovalov.example.mcp.oauth;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the RFC 9728 protected-resource metadata on exactly two paths (ADR 0035 §6): the path-inserted
 * one for the MCP endpoint, which the MCP specification has clients try first, and the root one they fall
 * back to.
 *
 * <p><b>Not Spring Security's {@code OAuth2ProtectedResourceMetadataFilter}.</b> That filter matches every
 * sub-path of the well-known prefix, presets {@code resource} from the request's own URL (its {@code Host})
 * and answers whenever it is installed. This contract needs the opposite of each: two paths, a document
 * from configuration only, and {@code 404} when discovery is off.
 */
@RestController
public class ProtectedResourceMetadataController {

    /** The root document's path. */
    public static final String ROOT_PATH = "/.well-known/oauth-protected-resource";

    /** The path-inserted document's path, for the resource at {@code /mcp}. */
    public static final String MCP_PATH = ROOT_PATH + ProtectedResourceDocument.MCP_ENDPOINT_PATH;

    private final ProtectedResourceDocument document;

    public ProtectedResourceMetadataController(ProtectedResourceDocument document) {
        this.document = document;
    }

    @GetMapping({ROOT_PATH, MCP_PATH})
    ResponseEntity<Map<String, Object>> metadata() {
        if (!document.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(document.body());
    }
}
