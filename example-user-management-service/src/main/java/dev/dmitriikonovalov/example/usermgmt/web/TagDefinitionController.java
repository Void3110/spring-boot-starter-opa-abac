package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.openapi.api.TagDefinitionApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.service.TagDefinitionService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to the dynamic tag dictionary — global keys, plus a team's keys when {@code teamId} is
 * given. The dictionary's <em>vocabulary</em> is public to any authenticated caller (reading what tags
 * exist is not sensitive); <em>defining</em> a team-scoped key is the dogfood-secured operation (ticket
 * 2). This is the read half; writes live on the team-scoped management controller.
 */
@RestController
public class TagDefinitionController implements TagDefinitionApi {

    private final TagDefinitionService tagDefinitions;

    public TagDefinitionController(TagDefinitionService tagDefinitions) {
        this.tagDefinitions = tagDefinitions;
    }

    @Override
    public ResponseEntity<List<TagDefinition>> listTagDefinitions(UUID teamId) {
        var result = tagDefinitions.list(teamId).stream().map(UserMgmtMapper::toDto).toList();
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<TagDefinition> getTagDefinition(UUID id) {
        return tagDefinitions.find(id)
                .map(UserMgmtMapper::toDto)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("Tag definition not found: " + id));
    }
}
