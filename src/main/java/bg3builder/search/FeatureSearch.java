package bg3builder.search;

import bg3builder.data.Indexes;
import bg3builder.data.Indexes.Source;
import bg3builder.model.Build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Given a set of target feature ids, finds the smallest build (fewest total
 * class levels, ≤ 12) that reaches all of them. If multiple builds tie at
 * the smallest level count, returns one (the first one BFS discovers — see
 * the canonical-form comment below).
 *
 * <h2>Why this is a graph problem</h2>
 * The build space is a graph where nodes are partial builds (multiset of
 * class-levels) and edges are "take one more level of class X." Source: total
 * legal builds with ≤12 levels across 12 classes is bounded but still large
 * (well into the tens of thousands of compositions). We BFS from the empty
 * build outward, level-by-level, returning the first node whose reachable-feature
 * set covers the targets.
 *
 * <h2>Why BFS works for "smallest"</h2>
 * BFS visits nodes in order of distance from the start. Distance here = total
 * class levels. So the first node we find that satisfies the target is by
 * definition the smallest build that satisfies it.
 *
 * <h2>Pruning to make it tractable</h2>
 * Without pruning, BFS over class-level multisets blows up fast. Two pruners:
 *
 * <ul>
 *   <li><b>Relevant classes only.</b> If the user wants {fireball, extra_attack},
 *       we look up sources for both, collect the set of classes that grant
 *       either of them, and only ever consider expanding into those classes.
 *       This cuts the branching factor from 12 to typically 2-4.</li>
 *   <li><b>Canonical form (visited set).</b> Wizard 1 + Fighter 1 and
 *       Fighter 1 + Wizard 1 are the same build (we don't track acquisition
 *       order, only composition). We canonicalize each build to a sorted
 *       string and skip duplicates. This collapses the search graph
 *       considerably.</li>
 * </ul>
 *
 * <h2>Starting class wrinkle</h2>
 * Two builds with the same composition but different starting classes can
 * have different reachable features (because of starting_class_bonus). So
 * the canonical form must include the starting class. In practice this
 * doubles or triples the visited set rather than collapsing it, but is
 * essential for correctness — a Fighter 1 / Wizard 5 starting Fighter has
 * heavy_armor_proficiency, starting Wizard does not.
 */
public class FeatureSearch {

    private final BuildExpander expander;
    private final Indexes indexes;

    /** Hard cap from the BG3 level system. */
    private static final int MAX_TOTAL_LEVELS = 12;

    public FeatureSearch(BuildExpander expander, Indexes indexes) {
        this.expander = expander;
        this.indexes = indexes;
    }

    /**
     * Searches for the smallest build that reaches all given features.
     * @return the build, or null if no build of ≤12 levels can reach all targets
     */
    public Build findSmallestBuild(Set<String> targetFeatures) {
        // Validate: every target must have at least one source, otherwise no build
        // can ever reach it.
        for (String target : targetFeatures) {
            if (indexes.sourcesOf(target).isEmpty()) {
                System.out.println("  [unreachable] feature '" + target
                        + "' has no source in any class — cannot build for it.");
                return null;
            }
        }

        // Collect the set of classes worth considering. Any class that doesn't
        // appear as a source for ANY target feature is irrelevant — adding levels
        // of it can never help. (Exception: a starting-class bonus might grant
        // something, but for current targets this is unlikely; we accept the
        // small risk to keep pruning sharp.)
        Set<String> relevantClasses = new HashSet<>();
        for (String target : targetFeatures) {
            for (Source s : indexes.sourcesOf(target)) {
                relevantClasses.add(s.classId());
            }
        }
        System.out.println("  Relevant classes: " + relevantClasses);

        // BFS from the empty build outward.
        Deque<Build> frontier = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        // Seed with one empty-1-level-of-class-X build per relevant class,
        // each one as its own starting class.
        for (String classId : relevantClasses) {
            Build seed = new Build.Builder().addLevels(classId, 1).build();
            String key = canonicalForm(seed);
            if (visited.add(key)) {
                frontier.add(seed);
            }
        }

        int nodesExplored = 0;
        while (!frontier.isEmpty()) {
            Build current = frontier.pollFirst();
            nodesExplored++;

            // Goal test: does this build reach all targets?
            Set<String> reachable = expander.reachableFeatures(current);
            if (reachable.containsAll(targetFeatures)) {
                System.out.println("  Found at " + current.totalLevels()
                        + " levels (" + nodesExplored + " nodes explored).");
                return current;
            }

            // Expand: for each relevant class, add one more level — if it doesn't
            // bust the cap.
            if (current.totalLevels() >= MAX_TOTAL_LEVELS) continue;
            for (String classId : relevantClasses) {
                Build next = addLevel(current, classId);
                String key = canonicalForm(next);
                if (visited.add(key)) {
                    frontier.add(next);
                }
            }
        }

        System.out.println("  Exhausted " + nodesExplored + " nodes, no build found.");
        return null;
    }

    /** Returns a new Build with one more level in the given class. */
    private static Build addLevel(Build base, String classId) {
        Map<String, Integer> nextLevels = new HashMap<>(base.classLevels());
        nextLevels.merge(classId, 1, Integer::sum);
        return new Build(nextLevels, base.startingClass(), base.subclasses());
    }

    /**
     * Canonical string for visited-set deduplication. Two builds with the same
     * class composition AND the same starting class hash to the same string,
     * regardless of how their classLevels map happens to be ordered.
     */
    private static String canonicalForm(Build b) {
        TreeMap<String, Integer> sorted = new TreeMap<>(b.classLevels());
        StringBuilder sb = new StringBuilder();
        sb.append("start=").append(b.startingClass()).append(';');
        for (Map.Entry<String, Integer> e : sorted.entrySet()) {
            sb.append(e.getKey()).append(':').append(e.getValue()).append(',');
        }
        return sb.toString();
    }

    /**
     * Pretty-prints a build for human consumption.
     */
    public static String describe(Build b) {
        if (b == null) return "(no build found)";
        StringBuilder sb = new StringBuilder();
        TreeMap<String, Integer> sorted = new TreeMap<>(b.classLevels());
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted.entrySet()) {
            String marker = e.getKey().equals(b.startingClass()) ? "*" : "";
            parts.add(marker + e.getKey() + " " + e.getValue());
        }
        sb.append(String.join(" / ", parts));
        sb.append("  (").append(b.totalLevels()).append(" total levels");
        sb.append(", * = starting class)");
        return sb.toString();
    }
}