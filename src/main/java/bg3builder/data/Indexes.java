package bg3builder.data;

import bg3builder.model.ChoiceList;
import bg3builder.model.ClassProgression;
import bg3builder.model.ClassProgression.Choice;
import bg3builder.model.ClassProgression.LevelEntry;
import bg3builder.model.ClassProgression.PoolReference;
import bg3builder.model.Feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Precomputed lookups built once at startup from the loaded data.
 * Search algorithms hit these instead of re-scanning the JSON-derived data
 * on every query.
 *
 * <p>Two indexes:
 * <ul>
 *   <li>{@link #sourcesOf(String)} — feature id -> all class/level slots that
 *       grant or offer it. Used by features-to-build search to know which
 *       classes can lead to a target feature.</li>
 *   <li>{@link #featuresWithTag(String)} — tag -> all feature ids carrying
 *       that tag. Used by tag-to-build search to find candidate features.</li>
 * </ul>
 */
public class Indexes {

    /** Where a single grant/choice of a feature lives. */
    public record Source(String classId, int level, Origin origin) {
        public enum Origin {
            /** Granted automatically (in the level's grants list). */
            GRANT,
            /** Granted as part of a grants_pool expansion. */
            GRANT_POOL,
            /** Offered as a pickable choice. */
            CHOICE,
            /** Granted only when this class is the build's starting class. */
            STARTING_BONUS_GRANT,
            /** Choosable only when this class is the build's starting class. */
            STARTING_BONUS_CHOICE
        }
    }

    private final Map<String, List<Source>> featureSources;
    private final Map<String, List<String>> tagIndex;

    private Indexes(Map<String, List<Source>> featureSources,
                    Map<String, List<String>> tagIndex) {
        this.featureSources = featureSources;
        this.tagIndex = tagIndex;
    }

    /** All sources of a feature, or empty list if the feature is unreachable. */
    public List<Source> sourcesOf(String featureId) {
        return featureSources.getOrDefault(featureId, List.of());
    }

    /** All feature ids carrying the given tag, or empty list if no matches. */
    public List<String> featuresWithTag(String tag) {
        return tagIndex.getOrDefault(tag, List.of());
    }

    /** Number of features with at least one source (sanity stat for diagnostics). */
    public int featuresWithSources() {
        return featureSources.size();
    }

    /** Number of distinct tags in the index. */
    public int distinctTags() {
        return tagIndex.size();
    }

    // ------------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------------

    /**
     * Builds both indexes from the loaded reference data.
     * Walks every class, every level, and every starting-class bonus, recording
     * a Source for each feature. Walks every feature for the tag index.
     */
    public static Indexes build(
            Map<String, Feature> features,
            Map<String, ClassProgression> classes,
            ChoiceList pools
    ) {
        Map<String, List<Source>> featureSources = new HashMap<>();
        Map<String, List<String>> tagIndex = new HashMap<>();

        // ---- Tag index: walk every feature ----
        for (Feature f : features.values()) {
            if (f.tags() == null) continue;
            for (String tag : f.tags()) {
                tagIndex.computeIfAbsent(tag, k -> new ArrayList<>()).add(f.id());
            }
        }

        // ---- Feature-source index: walk every class progression ----
        for (ClassProgression cls : classes.values()) {
            String classId = cls.id();

            // Walk normal levels
            for (Map.Entry<String, LevelEntry> e : cls.levels().entrySet()) {
                int level = Integer.parseInt(e.getKey());
                LevelEntry lev = e.getValue();

                recordGrants(featureSources, lev.grants(), classId, level,
                        Source.Origin.GRANT);
                recordPools(featureSources, lev.grantsPool(), classId, level,
                        Source.Origin.GRANT_POOL, features, pools);
                recordChoices(featureSources, lev.choices(), classId, level,
                        Source.Origin.CHOICE, features, pools);
            }

            // Walk starting class bonus (if any)
            if (cls.startingClassBonus() != null) {
                recordGrants(featureSources, cls.startingClassBonus().grants(),
                        classId, 1, Source.Origin.STARTING_BONUS_GRANT);
                recordChoices(featureSources, cls.startingClassBonus().choices(),
                        classId, 1, Source.Origin.STARTING_BONUS_CHOICE,
                        features, pools);
            }
        }

        // Make per-feature lists into immutable views (defensive — avoids accidental mutation later)
        Map<String, List<Source>> immutableSources = new HashMap<>();
        for (Map.Entry<String, List<Source>> e : featureSources.entrySet()) {
            immutableSources.put(e.getKey(), List.copyOf(e.getValue()));
        }
        Map<String, List<String>> immutableTags = new HashMap<>();
        for (Map.Entry<String, List<String>> e : tagIndex.entrySet()) {
            immutableTags.put(e.getKey(), List.copyOf(e.getValue()));
        }

        return new Indexes(immutableSources, immutableTags);
    }

    private static void recordGrants(Map<String, List<Source>> sink,
                                     List<String> ids, String classId, int level,
                                     Source.Origin origin) {
        for (String id : ids) {
            if (id.startsWith("_TODO")) continue;
            sink.computeIfAbsent(id, k -> new ArrayList<>())
                    .add(new Source(classId, level, origin));
        }
    }

    private static void recordPools(Map<String, List<Source>> sink,
                                    List<PoolReference> refs, String classId, int level,
                                    Source.Origin origin,
                                    Map<String, Feature> features, ChoiceList pools) {
        for (PoolReference ref : refs) {
            for (String id : expandPool(ref.fromList(), ref.filter(), features, pools)) {
                sink.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(new Source(classId, level, origin));
            }
        }
    }

    private static void recordChoices(Map<String, List<Source>> sink,
                                      List<Choice> choices, String classId, int level,
                                      Source.Origin origin,
                                      Map<String, Feature> features, ChoiceList pools) {
        for (Choice c : choices) {
            if (c.fromList() == null) continue;
            for (String id : expandPool(c.fromList(), c.filter(), features, pools)) {
                sink.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(new Source(classId, level, origin));
            }
        }
    }

    /**
     * Expands a pool reference, applying the spell-level filters that
     * BuildExpander also handles. Kept inline rather than shared with
     * BuildExpander to avoid coupling — both pieces are small.
     */
    private static List<String> expandPool(String poolName, Map<String, Object> filter,
                                           Map<String, Feature> features, ChoiceList pools) {
        List<String> raw = pools.get(poolName);
        if (filter == null || filter.isEmpty()) return raw;

        List<String> filtered = new ArrayList<>();
        for (String id : raw) {
            Feature f = features.get(id);
            if (f == null) continue;
            if (passesFilter(f, filter)) filtered.add(id);
        }
        return filtered;
    }

    private static boolean passesFilter(Feature f, Map<String, Object> filter) {
        Object exact = filter.get("spell_level");
        if (exact instanceof Integer n && f.spellLevel() != null && !f.spellLevel().equals(n)) {
            return false;
        }
        Object max = filter.get("spell_level_max");
        if (max instanceof Integer n && f.spellLevel() != null && f.spellLevel() > n) {
            return false;
        }
        Object min = filter.get("spell_level_min");
        if (min instanceof Integer n && f.spellLevel() != null && f.spellLevel() < n) {
            return false;
        }
        return true;
    }

    /**
     * Returns features that have NO source — useful for diagnostics, since
     * an unreachable feature usually means a typo or a missing class entry.
     */
    public Set<String> orphanFeatures(Map<String, Feature> features) {
        Set<String> orphans = new HashSet<>();
        for (String id : features.keySet()) {
            if (!featureSources.containsKey(id)) orphans.add(id);
        }
        return orphans;
    }
}