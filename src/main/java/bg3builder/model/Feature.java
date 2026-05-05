package bg3builder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Represents one acquirable thing in the game: a spell, feat, class feature,
 * fighting style, or proficiency.
 *
 * <p>This is immutable reference data loaded once from features.json at startup.
 * The id (key in JSON) is supplied separately by the loader since it lives at
 * the map-key level, not as a field inside each entry.
 *
 * @param id          unique identifier (e.g. "fireball")
 * @param name        display name (e.g. "Fireball")
 * @param type        one of "spell", "feat", "class_feature", "fighting_style", "proficiency"
 * @param tags        searchable tags (e.g. ["damage", "fire", "area"])
 * @param classes     for spells only: which classes can learn this spell
 * @param spellLevel  for spells only: 0 (cantrip) through 6
 * @param school      for spells only: e.g. "evocation", "abjuration"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Feature(
        String id,
        String name,
        String type,
        List<String> tags,
        List<String> classes,
        Integer spellLevel,
        String school
) {
    /** True if this feature is a spell. */
    public boolean isSpell() {
        return "spell".equals(type);
    }

    /** True if this spell can be learned by the given class. */
    public boolean isAvailableTo(String classId) {
        return classes != null && classes.contains(classId);
    }

    /** True if this feature has the given tag. */
    public boolean hasTag(String tag) {
        return tags != null && tags.contains(tag);
    }
}