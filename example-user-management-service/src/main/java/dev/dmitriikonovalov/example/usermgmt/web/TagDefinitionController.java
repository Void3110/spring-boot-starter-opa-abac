package dev.dmitriikonovalov.example.usermgmt.web;

import dev.dmitriikonovalov.example.usermgmt.domain.TagCardinality;
import dev.dmitriikonovalov.example.usermgmt.domain.TagValueType;
import dev.dmitriikonovalov.example.usermgmt.openapi.api.TagDefinitionApi;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinitionRequest;
import dev.dmitriikonovalov.example.usermgmt.openapi.model.TagDefinitionUpdate;
import dev.dmitriikonovalov.example.usermgmt.service.TagDefinitionService;
import dev.dmitriikonovalov.opaabac.security.OpaPreAuthorize;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dynamic tag dictionary's web surface.
 *
 * <ul>
 *   <li><b>read</b> (any authenticated caller) — the vocabulary is not sensitive: list globals (+ a team's
 *       keys), get one by id;</li>
 *   <li><b>team-scoped management</b> (dogfood-secured) — define/edit/delete a team's tag keys, each
 *       {@code @OpaPreAuthorize(action="team:define-tags", resourceType="'team'", resourceId="#teamId")}.
 *       The {@code define-tags} verb sits in the owner's <em>and</em> administrator's management ladder, so
 *       members/viewers are denied (403); global/system keys are immutable (409) in the service.</li>
 * </ul>
 *
 * Mirrors {@link RoleDefinitionController}: the controller is thin; shape + immutability rules live in
 * {@link TagDefinitionService}.
 */
@RestController
public class TagDefinitionController implements TagDefinitionApi {

    private final TagDefinitionService tagDefinitions;

    public TagDefinitionController(TagDefinitionService tagDefinitions) {
        this.tagDefinitions = tagDefinitions;
    }

    // --- read (any authenticated caller) --------------------------------------

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

    // --- team-scoped management (dogfood-secured: team:define-tags) ------------

    @Override
    @OpaPreAuthorize(action = "team:define-tags", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<List<TagDefinition>> listTeamTagDefinitions(UUID teamId) {
        var result = tagDefinitions.list(teamId).stream().map(UserMgmtMapper::toDto).toList();
        return ResponseEntity.ok(result);
    }

    @Override
    @OpaPreAuthorize(action = "team:define-tags", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<TagDefinition> createTeamTagDefinition(
            UUID teamId, TagDefinitionRequest request) {
        var created = tagDefinitions.defineForTeam(
                teamId,
                request.getKey(),
                valueType(request.getValueType()),
                cardinality(request.getCardinality()),
                request.getAllowedValues(),
                request.getValuePattern());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserMgmtMapper.toDto(created));
    }

    @Override
    @OpaPreAuthorize(action = "team:define-tags", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<TagDefinition> updateTeamTagDefinition(
            UUID teamId, String key, TagDefinitionUpdate request) {
        var updated = tagDefinitions.updateTeamKey(
                teamId,
                key,
                valueType(request.getValueType()),
                cardinality(request.getCardinality()),
                request.getAllowedValues(),
                request.getValuePattern());
        return ResponseEntity.ok(UserMgmtMapper.toDto(updated));
    }

    @Override
    @OpaPreAuthorize(action = "team:define-tags", resourceType = "'team'", resourceId = "#teamId")
    public ResponseEntity<Void> deleteTeamTagDefinition(UUID teamId, String key) {
        tagDefinitions.deleteTeamKey(teamId, key);
        return ResponseEntity.noContent().build();
    }

    private static TagValueType valueType(TagDefinitionRequest.ValueTypeEnum e) {
        return TagValueType.valueOf(e.getValue());
    }

    private static TagCardinality cardinality(TagDefinitionRequest.CardinalityEnum e) {
        return TagCardinality.valueOf(e.getValue());
    }

    private static TagValueType valueType(TagDefinitionUpdate.ValueTypeEnum e) {
        return TagValueType.valueOf(e.getValue());
    }

    private static TagCardinality cardinality(TagDefinitionUpdate.CardinalityEnum e) {
        return TagCardinality.valueOf(e.getValue());
    }
}
