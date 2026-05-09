package bg3builder.search;

import bg3builder.data.Indexes;
import bg3builder.data.Indexes.Source;
import bg3builder.model.Build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the smallest build (≤12 levels) that reaches a set of target features.
 *
 * <h2>Algorithm: A* with admissible heuristic</h2>
 * The build space is a graph: nodes are partial builds (multiset of class
 * levels + starting class), edges are "take one more level of class X."
 * The cost of a node is its total class-level count. We search outward from
 * the empty build using A* with priority = g(n) + h(n).
 *
 * <ul>
 *   <li><b>g(n)</b> = total class levels in build n.
 *   <li><b>h(n)</b> = a lower bound on additional levels needed to reach the goal.
 *       For each unmet target T, we compute the minimum additional levels needed
 *       in any class that sources T (given the build's current per-class levels).
 *       The maximum across all unmet targets is our heuristic — admissible
 *       because each unmet target must at least be paid for individually.
 * </ul>
 *
 * <h2>Pruning</h2>
 * <ol>
 *   <li><b>Relevant-classes filter (dynamic)</b>: at each node, we only consider
 *       expanding into classes that source at least one <em>unmet</em> target.
 *       Classes whose only contributions are already-satisfied targets aren't
 *       worth pursuing further.
 *   <li><b>Canonical form (visited set)</b>: two builds with the same class-level
 *       multiset and same starting class produce the same reachable set. We
 *       canonicalize to a sorted string and skip duplicates.
 *   <li><b>Admissible h-pruning</b>: if g(n) + h(n) > 12 (the BG3 cap), the
 *       node can never lead to a solution within budget. Skip it entirely.
 * </ol>
 *
 * <p>Returns null if no build of ≤12 total levels reaches the targets.
 */
public class FeatureSearch {

    private final BuildExpander expander;
    private final Indexes indexes;
    private static final int MAX_TOTAL_LEVELS = 12;

    private long lastNodesExplored = 0;

    public FeatureSearch(BuildExpander expander, Indexes indexes) {
        this.expander = expander;
        this.indexes = indexes;
    }

    /** Stats from the last search call. */
    public long lastNodesExplored() { return lastNodesExplored; }

    /**
     * Finds the smallest build that reaches every target feature.
     * @return the build, or null if no such build exists within MAX_TOTAL_LEVELS
     */
    public Build findSmallestBuild(Set<String> targetFeatures) {
        lastNodesExplored = 0;

        // Precondition: every target must be sourced by some class
        for (String t : targetFeatures) {
            if (indexes.sourcesOf(t).isEmpty()) return null;
        }

        // Compute the static "ever-relevant" set: classes that source at least
        // one target. Search will further dynamically narrow this per-node.
        Set<String> everRelevant = new HashSet<>();
        for (String t : targetFeatures) {
            everRelevant.addAll(indexes.classesThatSource(t));
        }

        // Open list: priority queue ordered by f(n) = g(n) + h(n)
        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingInt(n -> n.f));
        Set<String> closed = new HashSet<>();

        // Seed: one 1-level build per ever-relevant class, each as its starting class
        for (String classId : everRelevant) {
            Build seed = new Build.Builder().addLevels(classId, 1).build();
            int g = 1;
            int h = heuristic(seed, targetFeatures);
            int f = g + h;
            if (f > MAX_TOTAL_LEVELS) continue;
            open.add(new Node(seed, g, h, f));
        }

        while (!open.isEmpty()) {
            Node cur = open.poll();
            String key = canonicalForm(cur.build);
            if (!closed.add(key)) continue;
            lastNodesExplored++;

            // Goal test
            Set<String> reachable = expander.reachableFeatures(cur.build);
            if (reachable.containsAll(targetFeatures)) {
                return cur.build;
            }

            if (cur.g >= MAX_TOTAL_LEVELS) continue;

            // Dynamic relevant-classes filter: only expand into classes that source
            // at least one unmet target
            Set<String> unmetTargets = new HashSet<>(targetFeatures);
            unmetTargets.removeAll(reachable);
            Set<String> dynamicallyRelevant = new HashSet<>();
            for (String t : unmetTargets) {
                dynamicallyRelevant.addAll(indexes.classesThatSource(t));
            }

            for (String classId : dynamicallyRelevant) {
                Build next = addLevel(cur.build, classId);
                String nextKey = canonicalForm(next);
                if (closed.contains(nextKey)) continue;

                int g = cur.g + 1;
                int h = heuristic(next, targetFeatures);
                int f = g + h;
                if (f > MAX_TOTAL_LEVELS) continue;

                open.add(new Node(next, g, h, f));
            }
        }

        return null;
    }

    /**
     * Admissible heuristic: max over unmet targets T of the minimum additional
     * levels needed in any class to satisfy T from the current build state.
     */
    private int heuristic(Build build, Set<String> targets) {
        Set<String> reachable = expander.reachableFeatures(build);
        int worst = 0;
        for (String t : targets) {
            if (reachable.contains(t)) continue;
            int bestForThisTarget = Integer.MAX_VALUE;
            for (Source src : indexes.sourcesOf(t)) {
                int currentInClass = build.classLevels().getOrDefault(src.classId(), 0);
                int needed = Math.max(0, src.level() - currentInClass);
                if (needed < bestForThisTarget) bestForThisTarget = needed;
            }
            if (bestForThisTarget == Integer.MAX_VALUE) return Integer.MAX_VALUE;
            if (bestForThisTarget > worst) worst = bestForThisTarget;
        }
        return worst;
    }

    private static Build addLevel(Build base, String classId) {
        Map<String, Integer> nextLevels = new HashMap<>(base.classLevels());
        nextLevels.merge(classId, 1, Integer::sum);
        return new Build(nextLevels, base.startingClass(), base.subclasses());
    }

    private static String canonicalForm(Build b) {
        TreeMap<String, Integer> sorted = new TreeMap<>(b.classLevels());
        StringBuilder sb = new StringBuilder();
        sb.append("start=").append(b.startingClass()).append(';');
        for (Map.Entry<String, Integer> e : sorted.entrySet()) {
            sb.append(e.getKey()).append(':').append(e.getValue()).append(',');
        }
        return sb.toString();
    }

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
        sb.append("  (").append(b.totalLevels()).append(" total levels, * = starting)");
        return sb.toString();
    }

    /** A* node: g (cost so far), h (heuristic), f (priority). */
    private record Node(Build build, int g, int h, int f) {}
}