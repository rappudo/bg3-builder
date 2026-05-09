package bg3builder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * The progression of a subclass. Mirrors one entry in subclasses.json.
 * Same shape as ClassProgression's level structure (LevelEntry/Choice/PoolReference)
 * but with two extra fields: parent_class (which base class can pick this) and
 * available_at_level (the class level at which the subclass is chosen).
 *
 * <p>Subclass level keys are ABSOLUTE class levels — e.g. Berserker's "3" entry
 * applies when the parent Barbarian reaches level 3, not when the subclass has
 * "3 ranks of subclass".
 */
public record SubclassProgression(
        String id,
        String name,
        String parentClass,
        int availableAtLevel,
        Map<String, ClassProgression.LevelEntry> levels
) {

    public ClassProgression.LevelEntry levelEntry(int level) {
        return levels.get(String.valueOf(level));
    }

    /** Jackson-facing DTO — no id field, since id is the JSON map key. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dto(
            String name,
            @JsonProperty("parent_class") String parentClass,
            @JsonProperty("available_at_level") int availableAtLevel,
            Map<String, ClassProgression.LevelEntry> levels
    ) {
        public SubclassProgression toModel(String id) {
            return new SubclassProgression(id, name, parentClass, availableAtLevel, levels);
        }
    }
}