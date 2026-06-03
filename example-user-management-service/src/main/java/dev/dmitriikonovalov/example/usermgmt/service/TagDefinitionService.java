package dev.dmitriikonovalov.example.usermgmt.service;

import dev.dmitriikonovalov.example.usermgmt.domain.TagCardinality;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinition;
import dev.dmitriikonovalov.example.usermgmt.domain.TagDefinitionRepository;
import dev.dmitriikonovalov.example.usermgmt.domain.TagScope;
import dev.dmitriikonovalov.example.usermgmt.domain.TagValueType;
import dev.dmitriikonovalov.example.usermgmt.domain.TeamRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dynamic tag dictionary's service — the dictionary's <b>definition</b> layer.
 *
 * <ul>
 *   <li><b>read</b> (ticket 1): {@link #list(UUID)} / {@link #find(UUID)};</li>
 *   <li><b>team-scoped management</b> (ticket 2): {@link #defineForTeam} / {@link #updateTeamKey} /
 *       {@link #deleteTeamKey} — define/edit team keys at runtime, within the dictionary's shape rules.</li>
 * </ul>
 *
 * <p><b>System/global keys are immutable</b> — there is no API path to edit a {@code GLOBAL} (seeded,
 * {@code system=true}) key; an update/delete that resolves to one is rejected. <em>Who</em> may manage a
 * team's dictionary is decided by {@code @OpaPreAuthorize(team:define-tags)} on the controller (owner /
 * administrator); this service enforces the orthogonal shape + immutability rules. {@code @Transactional};
 * controllers stay thin.
 */
@Service
public class TagDefinitionService {

    /** A guard rail on enum size, mirroring AWS tag policies / governed-tag caps. */
    static final int MAX_ALLOWED_VALUES = 50;

    /** A conservative key shape: lower-case alphanumerics and dashes, starting with a letter. */
    private static final Pattern KEY_FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,98}[a-z0-9]");

    private final TagDefinitionRepository tagDefinitions;
    private final TeamRepository teams;

    public TagDefinitionService(TagDefinitionRepository tagDefinitions, TeamRepository teams) {
        this.tagDefinitions = tagDefinitions;
        this.teams = teams;
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

    /** Define a team-scoped tag key ({@code scope=TEAM}, {@code system=false}), within the shape rules. */
    @Transactional
    public TagDefinition defineForTeam(
            UUID teamId,
            String key,
            TagValueType valueType,
            TagCardinality cardinality,
            List<String> allowedValues,
            String valuePattern) {
        requireTeam(teamId);
        validateShape(key, valueType, cardinality, allowedValues, valuePattern);
        if (tagDefinitions.findByTeamIdAndKey(teamId, key).isPresent()) {
            throw new TagKeyConflictException(
                    "A tag key '" + key + "' already exists on team " + teamId);
        }
        return tagDefinitions.save(new TagDefinition(
                UUID.randomUUID(), key, TagScope.TEAM, teamId,
                valueType, cardinality, normalize(valueType, allowedValues), valuePattern, false));
    }

    /** Update a team-scoped tag key's shape. Global/system keys are immutable. */
    @Transactional
    public TagDefinition updateTeamKey(
            UUID teamId,
            String key,
            TagValueType valueType,
            TagCardinality cardinality,
            List<String> allowedValues,
            String valuePattern) {
        requireTeam(teamId);
        TagDefinition existing = requireTeamKey(teamId, key);
        validateShape(key, valueType, cardinality, allowedValues, valuePattern);
        existing.setValueType(valueType);
        existing.setCardinality(cardinality);
        existing.setAllowedValues(normalize(valueType, allowedValues));
        existing.setValuePattern(valuePattern);
        return tagDefinitions.save(existing);
    }

    /** Delete a team-scoped tag key. Global/system keys are immutable. */
    @Transactional
    public void deleteTeamKey(UUID teamId, String key) {
        requireTeam(teamId);
        tagDefinitions.delete(requireTeamKey(teamId, key));
    }

    private void requireTeam(UUID teamId) {
        if (!teams.existsById(teamId)) {
            throw new IllegalArgumentException("Team not found: " + teamId);
        }
    }

    /**
     * A mutable team key by name. A team key takes precedence: if one exists it is returned (and, being
     * team-scoped, is mutable). Otherwise, if a global key with that name exists, the edit is rejected as
     * immutable (an owner cannot edit a seeded key by addressing it through the team route); if neither
     * exists it is a 404.
     */
    private TagDefinition requireTeamKey(UUID teamId, String key) {
        Optional<TagDefinition> teamKey = tagDefinitions.findByTeamIdAndKey(teamId, key);
        if (teamKey.isPresent()) {
            return teamKey.get();
        }
        if (tagDefinitions.findByTeamIdIsNullAndKey(key).isPresent()) {
            throw new TagDefinitionImmutableException(key);
        }
        throw new TagDefinitionNotFoundException(teamId, key);
    }

    /** Validate the definition's own shape (independent of any submitted resource value). */
    private static void validateShape(
            String key,
            TagValueType valueType,
            TagCardinality cardinality,
            List<String> allowedValues,
            String valuePattern) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new InvalidTagDefinitionException(
                    "Tag key must be lower-case kebab-case (a-z, 0-9, '-'): '" + key + "'");
        }
        if (valueType == null || cardinality == null) {
            throw new InvalidTagDefinitionException("valueType and cardinality are required");
        }
        if (valueType == TagValueType.ENUM) {
            if (allowedValues == null || allowedValues.isEmpty()) {
                throw new InvalidTagDefinitionException(
                        "An ENUM tag key requires a non-empty allowedValues set");
            }
            if (allowedValues.size() > MAX_ALLOWED_VALUES) {
                throw new InvalidTagDefinitionException(
                        "allowedValues exceeds the cap of " + MAX_ALLOWED_VALUES);
            }
            if (allowedValues.stream().anyMatch(v -> v == null || v.isBlank())) {
                throw new InvalidTagDefinitionException("allowedValues may not contain blank entries");
            }
            if (valuePattern != null && !valuePattern.isBlank()) {
                throw new InvalidTagDefinitionException(
                        "valuePattern applies only to STRING keys, not ENUM");
            }
        } else { // STRING
            if (allowedValues != null && !allowedValues.isEmpty()) {
                throw new InvalidTagDefinitionException(
                        "allowedValues applies only to ENUM keys, not STRING");
            }
            if (valuePattern != null && !valuePattern.isBlank()) {
                try {
                    Pattern.compile(valuePattern);
                } catch (PatternSyntaxException ex) {
                    throw new InvalidTagDefinitionException("valuePattern is not a valid regex");
                }
            }
        }
    }

    /** ENUM keeps its set; STRING never stores allowed values. */
    private static List<String> normalize(TagValueType valueType, List<String> allowedValues) {
        if (valueType == TagValueType.ENUM) {
            return allowedValues == null ? List.of() : List.copyOf(allowedValues);
        }
        return List.of();
    }
}
