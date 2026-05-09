package bg3builder.search;

import bg3builder.model.Build;
import bg3builder.model.ChoiceList;
import bg3builder.model.ClassProgression;
import bg3builder.model.ClassProgression.Choice;
import bg3builder.model.ClassProgression.LevelEntry;
import bg3builder.model.ClassProgression.PoolReference;
import bg3builder.model.Feature;
import bg3builder.model.SubclassProgression;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the set of features reachable by a build.
 *
 * <p>"Reachable" = the build either gets the feature automatically (grants),
 * gets every member of a pool (grants_pool), or could pick the feature from
 * one of its choice slots (choices). Capability semantics applies — every
 * member of a choice pool counts as reachable.
 *
 * <p>Subclass features: for each base class in the build, ALL subclasses with
 * that parent class contribute their level entries (up to the build's level
 * in that class). This is the "capability across subclasses" choice — the
 * build's reachable set includes features from every possible subclass pick.
 * Materialization later resolves which subclass to actually name in output.
 */
public class BuildExpander {

    private final Map<String, Feature> features;
    private final Map<String, ClassProgression> classes;
    private final Map<String, List<SubclassProgression>> subclassesByParent;
    private final ChoiceList pools;

    public BuildExpander(
            Map<String, Feature> features,
            Map<String, ClassProgression> classes,
            Map<String, SubclassProgression> subclasses,
            ChoiceList pools
    ) {
        this.features = features;
        this.classes = classes;
        this.pools = pools;

        // Group subclasses by parent class for fast lookup during expansion
        this.subclassesByParent = new java.util.HashMap<>();
        for (SubclassProgression sub : subclasses.values()) {
            subclassesByParent
                    .computeIfAbsent(sub.parentClass(), k -> new ArrayList<>())
                    .add(sub);
        }
    }

    /**
     * Computes the full set of features reachable by this build, walking both
     * base class progressions and all subclass progressions for each class.
     */
    public Set<String> reachableFeatures(Build build) {
        Set<String> reachable = new HashSet<>();

        for (Map.Entry<String, Integer> entry : build.classLevels().entrySet()) {
            String classId = entry.getKey();
            int taken = entry.getValue();
            ClassProgression cls = classes.get(classId);
            if (cls == null) continue;

            // Walk base class levels
            for (int lvl = 1; lvl <= taken; lvl++) {
                LevelEntry lev = cls.levelEntry(lvl);
                if (lev == null) continue;
                addFromLevelEntry(lev, reachable);
            }

            // Apply starting class bonus if applicable
            if (classId.equals(build.startingClass()) && cls.startingClassBonus() != null) {
                addAll(cls.startingClassBonus().grants(), reachable);
                addFromChoices(cls.startingClassBonus().choices(), reachable);
            }

            // Walk every subclass with this parent class
            // (capability semantics — all subclass options are "reachable")
            List<SubclassProgression> subs = subclassesByParent.getOrDefault(classId, List.of());
            for (SubclassProgression sub : subs) {
                if (taken < sub.availableAtLevel()) continue;
                for (int lvl = 1; lvl <= taken; lvl++) {
                    LevelEntry lev = sub.levelEntry(lvl);
                    if (lev == null) continue;
                    addFromLevelEntry(lev, reachable);
                }
            }
        }

        return reachable;
    }

    private void addFromLevelEntry(LevelEntry lev, Set<String> reachable) {
        for (String id : lev.grants()) {
            if (!id.startsWith("_TODO")) reachable.add(id);
        }
        for (PoolReference ref : lev.grantsPool()) {
            for (String id : poolMembers(ref.fromList(), ref.filter())) {
                reachable.add(id);
            }
        }
        addFromChoices(lev.choices(), reachable);
    }

    private void addFromChoices(List<Choice> choices, Set<String> reachable) {
        for (Choice c : choices) {
            if (c.fromList() == null) continue;
            for (String id : poolMembers(c.fromList(), c.filter())) {
                reachable.add(id);
            }
        }
    }

    private List<String> poolMembers(String poolName, Map<String, Object> filter) {
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

    private boolean passesFilter(Feature f, Map<String, Object> filter) {
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

    private static void addAll(List<String> from, Set<String> to) {
        for (String s : from) {
            if (!s.startsWith("_TODO")) to.add(s);
        }
    }
}