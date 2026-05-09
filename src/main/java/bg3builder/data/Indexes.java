package bg3builder.data;

import bg3builder.model.ChoiceList;
import bg3builder.model.ClassProgression;
import bg3builder.model.ClassProgression.Choice;
import bg3builder.model.ClassProgression.LevelEntry;
import bg3builder.model.ClassProgression.PoolReference;
import bg3builder.model.Feature;
import bg3builder.model.SubclassProgression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Precomputed lookups built once at startup.
 *
 * <p>Indexes track:
 * <ul>
 *   <li><b>featureSources</b> — feature id → list of (class/subclass + level + origin)
 *       slots that source this feature. Used by FeatureSearch for relevance pruning
 *       and admissible heuristic computation.</li>
 *   <li><b>tagIndex</b> — tag → list of feature ids carrying that tag. Used by
 *       TagSearch to derive candidate features.</li>
 *   <li><b>tagClassCount</b> — (tag, classId) → count of features the class can
 *       reach at the configured max level that carry the tag. Used by TagSearch
 *       for top-K class pruning.</li>
 * </ul>
 */
public class Indexes {

    /** A source for a feature — either a base-class slot or a subclass slot. */
    public record Source(String classId, String subclassId, int level, Origin origin) {
        public enum Origin {
            GRANT, GRANT_POOL, CHOICE,
            STARTING_BONUS_GRANT, STARTING_BONUS_CHOICE,
            SUBCLASS_GRANT, SUBCLASS_GRANT_POOL, SUBCLASS_CHOICE
        }

        public boolean isSubclass() { return subclassId != null; }
    }

    private final Map<String, List<Source>> featureSources;
    private final Map<String, List<String>> tagIndex;
    private final Set<String> allClassIds;

    private Indexes(Map<String, List<Source>> featureSources,
                    Map<String, List<String>> tagIndex,
                    Set<String> allClassIds) {
        this.featureSources = featureSources;
        this.tagIndex = tagIndex;
        this.allClassIds = allClassIds;
    }

    public List<Source> sourcesOf(String featureId) {
        return featureSources.getOrDefault(featureId, List.of());
    }

    public List<String> featuresWithTag(String tag) {
        return tagIndex.getOrDefault(tag, List.of());
    }

    public Set<String> allClassIds() { return allClassIds; }
    public int featuresWithSources() { return featureSources.size(); }
    public int distinctTags() { return tagIndex.size(); }

    /**
     * Returns the unique base class ids that source the given feature
     * (subclass sources count as their parent class).
     */
    public Set<String> classesThatSource(String featureId) {
        Set<String> result = new HashSet<>();
        for (Source s : sourcesOf(featureId)) {
            result.add(s.classId());
        }
        return result;
    }

    /**
     * Minimum class level at which a feature first becomes reachable
     * for the given class. Returns Integer.MAX_VALUE if the class never sources it.
     */
    public int minLevelForFeatureInClass(String featureId, String classId) {
        int min = Integer.MAX_VALUE;
        for (Source s : sourcesOf(featureId)) {
            if (s.classId().equals(classId) && s.level() < min) min = s.level();
        }
        return min;
    }

    public static Indexes build(
            Map<String, Feature> features,
            Map<String, ClassProgression> classes,
            Map<String, SubclassProgression> subclasses,
            ChoiceList pools
    ) {
        Map<String, List<Source>> featureSources = new HashMap<>();
        Map<String, List<String>> tagIndex = new HashMap<>();

        // Tag index
        for (Feature f : features.values()) {
            if (f.tags() == null) continue;
            for (String tag : f.tags()) {
                tagIndex.computeIfAbsent(tag, k -> new ArrayList<>()).add(f.id());
            }
        }

        // Walk base classes
        for (ClassProgression cls : classes.values()) {
            String classId = cls.id();
            for (Map.Entry<String, LevelEntry> e : cls.levels().entrySet()) {
                int level = Integer.parseInt(e.getKey());
                LevelEntry lev = e.getValue();
                recordGrants(featureSources, lev.grants(), classId, null, level,
                        Source.Origin.GRANT);
                recordPools(featureSources, lev.grantsPool(), classId, null, level,
                        Source.Origin.GRANT_POOL, features, pools);
                recordChoices(featureSources, lev.choices(), classId, null, level,
                        Source.Origin.CHOICE, features, pools);
            }
            if (cls.startingClassBonus() != null) {
                recordGrants(featureSources, cls.startingClassBonus().grants(),
                        classId, null, 1, Source.Origin.STARTING_BONUS_GRANT);
                recordChoices(featureSources, cls.startingClassBonus().choices(),
                        classId, null, 1, Source.Origin.STARTING_BONUS_CHOICE,
                        features, pools);
            }
        }

        // Walk subclasses
        for (SubclassProgression sub : subclasses.values()) {
            String classId = sub.parentClass();
            String subId = sub.id();
            for (Map.Entry<String, LevelEntry> e : sub.levels().entrySet()) {
                int level = Integer.parseInt(e.getKey());
                LevelEntry lev = e.getValue();
                recordGrants(featureSources, lev.grants(), classId, subId, level,
                        Source.Origin.SUBCLASS_GRANT);
                recordPools(featureSources, lev.grantsPool(), classId, subId, level,
                        Source.Origin.SUBCLASS_GRANT_POOL, features, pools);
                recordChoices(featureSources, lev.choices(), classId, subId, level,
                        Source.Origin.SUBCLASS_CHOICE, features, pools);
            }
        }

        // Make immutable copies
        Map<String, List<Source>> immutableSources = new HashMap<>();
        for (Map.Entry<String, List<Source>> e : featureSources.entrySet()) {
            immutableSources.put(e.getKey(), List.copyOf(e.getValue()));
        }
        Map<String, List<String>> immutableTags = new HashMap<>();
        for (Map.Entry<String, List<String>> e : tagIndex.entrySet()) {
            immutableTags.put(e.getKey(), List.copyOf(e.getValue()));
        }

        return new Indexes(immutableSources, immutableTags, Set.copyOf(classes.keySet()));
    }

    private static void recordGrants(Map<String, List<Source>> sink,
                                     List<String> ids,
                                     String classId, String subclassId, int level,
                                     Source.Origin origin) {
        for (String id : ids) {
            if (id.startsWith("_TODO")) continue;
            sink.computeIfAbsent(id, k -> new ArrayList<>())
                    .add(new Source(classId, subclassId, level, origin));
        }
    }

    private static void recordPools(Map<String, List<Source>> sink,
                                    List<PoolReference> refs,
                                    String classId, String subclassId, int level,
                                    Source.Origin origin,
                                    Map<String, Feature> features, ChoiceList pools) {
        for (PoolReference ref : refs) {
            for (String id : expandPool(ref.fromList(), ref.filter(), features, pools)) {
                sink.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(new Source(classId, subclassId, level, origin));
            }
        }
    }

    private static void recordChoices(Map<String, List<Source>> sink,
                                      List<Choice> choices,
                                      String classId, String subclassId, int level,
                                      Source.Origin origin,
                                      Map<String, Feature> features, ChoiceList pools) {
        for (Choice c : choices) {
            if (c.fromList() == null) continue;
            for (String id : expandPool(c.fromList(), c.filter(), features, pools)) {
                sink.computeIfAbsent(id, k -> new ArrayList<>())
                        .add(new Source(classId, subclassId, level, origin));
            }
        }
    }

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
        if (exact instanceof Integer n && f.spellLevel() != null && !f.spellLevel().equals(n)) return false;
        Object max = filter.get("spell_level_max");
        if (max instanceof Integer n && f.spellLevel() != null && f.spellLevel() > n) return false;
        Object min = filter.get("spell_level_min");
        if (min instanceof Integer n && f.spellLevel() != null && f.spellLevel() < n) return false;
        return true;
    }

    public Set<String> orphanFeatures(Map<String, Feature> features) {
        Set<String> orphans = new HashSet<>();
        for (String id : features.keySet()) {
            if (!featureSources.containsKey(id)) orphans.add(id);
        }
        return orphans;
    }
}