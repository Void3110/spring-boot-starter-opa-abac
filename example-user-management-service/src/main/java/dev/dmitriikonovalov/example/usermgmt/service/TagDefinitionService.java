package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinitionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dynamic tag dictionary's read surface (ticket 1). Management writes (define/edit team-scoped keys)
 * are added in ticket 2; the applicable-definitions resolution for tag assignment is added in ticket 3.
 * {@code @Transactional}; controllers stay thin.
 */
@Service
public class TagDefinitionService {

    private final TagDefinitionRepository tagDefinitions;

    public TagDefinitionService(TagDefinitionRepository tagDefinitions) {
        this.tagDefinitions = tagDefinitions;
    }

    /**
     * The applicable dictionary: all global keys, plus a single team's keys when {@code teamId} is given.
     * This is the same set tag assignment validates against (ticket 3), exposed read-only here.
     */
    @Transactional(readOnly = true)
    public List<TagDefinition> list(UUID teamId) {
        if (teamId == null) {
            return tagDefinitions.findByTeamIdIsNull();
        }
        return tagDefinitions.findByTeamIdIsNullOrTeamId(teamId);
    }

    @Transactional(readOnly = true)
    public Optional<TagDefinition> find(UUID id) {
        return tagDefinitions.findById(id);
    }
}
