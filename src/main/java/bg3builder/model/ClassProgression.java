package bg3builder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The progression of a single class. Mirrors one top-level entry of classes.json
 * (e.g. the "wizard" object). Loaded once at startup, immutable thereafter.
 *
 * <p>Jackson reads the JSON into the inner {@link Dto} class (which has only
 * the fields present in the JSON, no id), then the loader builds a
 * ClassProgression from the Dto plus the id pulled from the surrounding map key.
 */
public record ClassProgression(
        String id,
        String name,
        Map<String, LevelEntry> levels,
        StartingClassBonus startingClassBonus
) {

    public LevelEntry levelEntry(int level) {
        return levels.get(String.valueOf(level));
    }

    /** Jackson-facing data shape — matches the JSON exactly, no id field. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dto(
            String name,
            Map<String, LevelEntry> levels,
            @JsonProperty("starting_class_bonus") StartingClassBonus startingClassBonus
    ) {
        public ClassProgression toModel(String id) {
            return new ClassProgression(id, name, levels, startingClassBonus);
        }
    }

    /** What is gained at one level of a class. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LevelEntry(
            List<String> grants,
            @JsonProperty("grants_pool") List<PoolReference> grantsPool,
            List<Choice> choices
    ) {
        public LevelEntry {
            grants = grants == null ? List.of() : grants;
            grantsPool = grantsPool == null ? List.of() : grantsPool;
            choices = choices == null ? List.of() : choices;
        }
    }

    /** Extra block applied only when this class is the build's starting class. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StartingClassBonus(
            List<String> grants,
            List<Choice> choices
    ) {
        public StartingClassBonus {
            grants = grants == null ? List.of() : grants;
            choices = choices == null ? List.of() : choices;
        }

        public static StartingClassBonus empty() {
            return new StartingClassBonus(List.of(), List.of());
        }
    }

    /**
     * One pickable choice — references a pool, optionally narrows by filter
     * (e.g. spell_level: 3). The "pick" count is captured but ignored in
     * capability-based search (every option counts as reachable).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            Object pick,
            @JsonProperty("from_list") String fromList,
            Map<String, Object> filter
    ) {
        public Choice {
            filter = filter == null ? Collections.emptyMap() : filter;
        }
    }

    /** A grants_pool entry — same shape as Choice but without "pick". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PoolReference(
            @JsonProperty("from_list") String fromList,
            Map<String, Object> filter
    ) {
        public PoolReference {
            filter = filter == null ? Collections.emptyMap() : filter;
        }
    }
}