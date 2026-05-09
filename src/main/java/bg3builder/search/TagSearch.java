package bg3builder.search;

import bg3builder.data.Indexes;
import bg3builder.model.Build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the build (within a level cap) that maximizes coverage of features
 * carrying the user's target tags.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Build the candidate set: all features tagged with any target tag.</li>
 *   <li>Score each base class by how many candidate features it can reach at
 *       <em>full level cap</em>. Sort descending. Take the top K classes
 *       (K = 4 by default).</li>
 *   <li>Enumerate every multi-class composition of those K classes summing
 *       to ≤ levelCap, with each composition expanded over all valid
 *       starting-class choices.</li>
 *   <li>Score each enumerated build by |reachable ∩ candidates|. Sort by
 *       (score DESC, totalLevels ASC). Return top N.</li>
 * </ol>
 *
 * <h2>Why top-K class pruning is acceptable</h2>
 * The full enumeration over all 12 classes scales as O(C(N+11, 11) × 12)
 * where N is the cap. That's ~74k builds at cap 6 and explodes at cap 8+.
 * Top-K pruning brings it to O(C(N+K-1, K-1) × K) — at K=4, cap=6 that's
 * ~84 × 4 = 336 builds. Roughly 200× faster.
 *
 * <p>The trade-off: we might miss optimal solutions where a "low-score"
 * class actually pairs well with the top classes. In practice, the top
 * 4 classes by tag coverage essentially always contain the optimal pick —
 * a class that doesn't help with the tags individually rarely helps in
 * combination either.
 *
 * <p>For demonstration purposes, the search exposes both modes (full
 * enumeration and top-K pruned) so the speedup can be measured.
 */
public class TagSearch {

    public record ScoredBuild(Build build, int score, Set<String> matchedFeatures) {}

    private final BuildExpander expander;
    private final Indexes indexes;
    private static final int DEFAULT_TOP_K = 4;

    private long lastBuildsEvaluated = 0;

    public TagSearch(BuildExpander expander, Indexes indexes) {
        this.expander = expander;
        this.indexes = indexes;
    }

    public long lastBuildsEvaluated() { return lastBuildsEvaluated; }

    /**
     * Pruned search: only enumerates compositions of the top-K classes by
     * candidate-feature coverage. Default K = 4.
     */
    public List<ScoredBuild> findBestBuilds(Set<String> targetTags, int levelCap, int topN) {
        return findBestBuilds(targetTags, levelCap, topN, DEFAULT_TOP_K);
    }

    public List<ScoredBuild> findBestBuilds(Set<String> targetTags, int levelCap,
                                            int topN, int topK) {
        lastBuildsEvaluated = 0;

        // Step 1: candidate features (union of features-with-target-tags)
        Set<String> candidates = new HashSet<>();
        for (String tag : targetTags) {
            candidates.addAll(indexes.featuresWithTag(tag));
        }
        if (candidates.isEmpty()) return List.of();

        // Step 2: score each class, take top K
        List<ClassScore> classScores = new ArrayList<>();
        for (String classId : indexes.allClassIds()) {
            int score = scoreClassAtLevel(classId, levelCap, candidates);
            classScores.add(new ClassScore(classId, score));
        }
        classScores.sort(Comparator.<ClassScore>comparingInt(ClassScore::score).reversed());
        List<String> topClasses = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, classScores.size()); i++) {
            topClasses.add(classScores.get(i).classId());
        }

        // Step 3: enumerate compositions of top-K classes
        List<Build> allBuilds = new ArrayList<>();
        enumerate(topClasses, 0, new TreeMap<>(), levelCap, allBuilds);

        // Step 4: score each build
        List<ScoredBuild> scored = new ArrayList<>();
        for (Build b : allBuilds) {
            Set<String> reachable = expander.reachableFeatures(b);
            Set<String> matched = new HashSet<>(reachable);
            matched.retainAll(candidates);
            scored.add(new ScoredBuild(b, matched.size(), matched));
        }
        lastBuildsEvaluated = scored.size();

        scored.sort(Comparator.<ScoredBuild>comparingInt(ScoredBuild::score).reversed()
                .thenComparingInt(sb -> sb.build().totalLevels()));

        return scored.subList(0, Math.min(topN, scored.size()));
    }

    /**
     * Scores a class by how many candidate features a single-class build of that
     * class at the given level can reach.
     */
    private int scoreClassAtLevel(String classId, int level, Set<String> candidates) {
        Build single = Build.singleClass(classId, Math.min(level, 12));
        Set<String> reachable = expander.reachableFeatures(single);
        int score = 0;
        for (String c : candidates) if (reachable.contains(c)) score++;
        return score;
    }

    /**
     * Recursively enumerate compositions of the given classes, summing to ≤ remaining.
     * For each composition, generate one Build per starting-class choice.
     */
    private void enumerate(List<String> classIds, int idx,
                           TreeMap<String, Integer> partial, int remaining,
                           List<Build> sink) {
        if (idx == classIds.size()) {
            if (partial.isEmpty()) return;
            for (String startingClass : partial.keySet()) {
                sink.add(new Build(partial, startingClass, java.util.Map.of()));
            }
            return;
        }
        String classId = classIds.get(idx);
        for (int n = 0; n <= remaining; n++) {
            if (n > 0) partial.put(classId, n);
            enumerate(classIds, idx + 1, partial, remaining - n, sink);
            if (n > 0) partial.remove(classId);
        }
    }

    private record ClassScore(String classId, int score) {}
}