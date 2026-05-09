package bg3builder.search;

import java.util.List;
import java.util.Map;

/**
 * The materialized output of a search — a per-level breakdown of a build,
 * showing what's granted at each level and which choices to make to reach
 * the user's targets.
 *
 * <p>Designed to be Jackson-serializable to a JSON output that a frontend
 * could consume. Field names use camelCase; Jackson can be configured for
 * snake_case output if desired.
 */
public record BuildPlan(
        boolean found,
        int totalLevels,
        String startingClass,
        Map<String, Integer> classLevels,
        Map<String, String> subclassPicks,
        List<LevelStep> progression,
        List<String> targets,
        List<String> targetsSatisfied,
        List<String> targetsUnsatisfied,
        long searchTimeMs,
        long nodesExplored
) {

    /**
     * One level of the build's progression — what was granted, what was chosen.
     *
     * @param level         the player level (1..12, ascending)
     * @param classId       the class taken at this level
     * @param classLevel    the class-specific level (e.g. wizard 3 = classLevel 3)
     * @param starting      true if this is the build's starting class level (level 1 of starting class)
     * @param subclassPick  if this level triggers a subclass selection, the chosen id
     * @param grants        features granted automatically at this level
     * @param choices       meaningful choice slots (those touching a target, plus subclass picks)
     */
    public record LevelStep(
            int level,
            String classId,
            int classLevel,
            boolean starting,
            String subclassPick,
            List<GrantedFeature> grants,
            List<MeaningfulChoice> choices
    ) {}

    /**
     * One feature granted at a level. {@code targetMatch} is true if this
     * feature is one of the user's stated targets — frontends can highlight it.
     * {@code fromSubclass} is null for base class grants, otherwise the subclass id.
     */
    public record GrantedFeature(
            String id,
            String name,
            boolean targetMatch,
            String fromSubclass
    ) {}

    /**
     * One choice slot worth presenting to the user.
     *
     * <p>Two flavors:
     * <ul>
     *   <li>Target-touching: at least one option in the pool matches a user target.
     *       {@code recommended} lists those targets so the user knows what to pick.</li>
     *   <li>Subclass pick: the user must choose a subclass for the class.
     *       {@code recommended} contains the subclass that best fits the targets,
     *       or null if no target requires a specific subclass.</li>
     * </ul>
     *
     * <p>Choices that don't touch any target and aren't subclass picks are
     * omitted — they're "pick whatever you want" noise.
     */
    public record MeaningfulChoice(
            String fromPool,
            Map<String, Object> filter,
            int pickCount,
            List<String> recommended,
            String reason
    ) {}
}