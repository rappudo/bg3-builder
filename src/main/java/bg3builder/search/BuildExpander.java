package bg3builder.search;

import bg3builder.model.Build;
import bg3builder.model.ChoiceList;
import bg3builder.model.ClassProgression;
import bg3builder.model.ClassProgression.Choice;
import bg3builder.model.ClassProgression.LevelEntry;
import bg3builder.model.ClassProgression.PoolReference;
import bg3builder.model.Feature;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the set of features reachable by a build.
 *
 * <p>"Reachable" = the build either gets the feature automatically (grants),
 * gets every member of a pool (grants_pool), or could pick the feature from
 * one of its choice slots (choices). Capability-based: we don't track which
 * spells/feats the build "actually picked" — every choosable option counts
 * as reachable, because the algorithm answers "could this build acquire X?"
 * not "did this build acquire X?".
 *
 * <p>Reference data (features map, classes, subclasses, choice lists) is
 * loaded once at startup; an instance is reusable across many build queries.
 */
public class BuildExpander {

    private final Map<String, Feature> features;
    private final Map<String, ClassProgression> classes;
    // Subclasses are not yet implemented — placeholder for the real data later.
    private final Map<String, ?> subclasses;
    private final ChoiceList pools;

    public BuildExpander(
            Map<String, Feature> features,
            Map<String, ClassProgression> classes,
            Map<String, ?> subclasses,
            ChoiceList pools
    ) {
        this.features = features;
        this.classes = classes;
        this.subclasses = subclasses;
        this.pools = pools;
    }

    /**
     * Computes the full set of features reachable by this build.
     */
    public Set<String> reachableFeatures(Build build) {
        Set<String> reachable = new HashSet<>();

        for (Map.Entry<String, Integer> entry : build.classLevels().entrySet()) {
            String classId = entry.getKey();
            int taken = entry.getValue();
            ClassProgression cls = classes.get(classId);
            if (cls == null) continue;

            // Walk levels 1..taken and accumulate grants/grants_pool/choices.
            for (int lvl = 1; lvl <= taken; lvl++) {
                LevelEntry lev = cls.levelEntry(lvl);
                if (lev == null) continue;
                addFromLevelEntry(lev, reachable);
            }

            // If this is the build's starting class, also apply the bonus block.
            if (classId.equals(build.startingClass()) && cls.startingClassBonus() != null) {
                addAll(cls.startingClassBonus().grants(), reachable);
                addFromChoices(cls.startingClassBonus().choices(), reachable);
            }
        }

        // Filter out features whose `requires` field isn't satisfied.
        // (Currently the model has `requires` as an attribute on Feature itself;
        // implementation will plug in once that field is added to the Feature record.)
        // For now this is a no-op — we'll implement it when handling deepened_pact.

        return reachable;
    }

    /** Walks one level-entry and adds everything it makes reachable. */
    private void addFromLevelEntry(LevelEntry lev, Set<String> reachable) {
        // 1. Direct grants — always added (skip _TODO placeholders just in case).
        for (String id : lev.grants()) {
            if (!id.startsWith("_TODO")) {
                reachable.add(id);
            }
        }

        // 2. grants_pool — every member of the pool, optionally filtered, is added.
        for (PoolReference ref : lev.grantsPool()) {
            for (String id : poolMembers(ref.fromList(), ref.filter())) {
                reachable.add(id);
            }
        }

        // 3. choices — every member of the pool is reachable (capability semantics).
        addFromChoices(lev.choices(), reachable);
    }

    /** Walks a list of choices and treats every option as reachable. */
    private void addFromChoices(List<Choice> choices, Set<String> reachable) {
        for (Choice c : choices) {
            if (c.fromList() == null) continue; // _TODO placeholders may have nulls
            for (String id : poolMembers(c.fromList(), c.filter())) {
                reachable.add(id);
            }
        }
    }

    /**
     * Returns the contents of a pool, narrowed by an optional filter.
     * Filters supported:
     *   - {"spell_level": N}     — only spells of exactly this level
     *   - {"spell_level_max": N} — only spells up to and including this level
     *   - {"spell_level_min": N} — only spells at or above this level
     */
    private List<String> poolMembers(String poolName, Map<String, Object> filter) {
        List<String> raw = pools.get(poolName);
        if (filter == null || filter.isEmpty()) {
            return raw;
        }

        // Apply spell_level filters by looking up each member in features.
        List<String> filtered = new java.util.ArrayList<>();
        for (String id : raw) {
            Feature f = features.get(id);
            if (f == null) continue; // broken pool reference; skip silently for now
            if (passesFilter(f, filter)) {
                filtered.add(id);
            }
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