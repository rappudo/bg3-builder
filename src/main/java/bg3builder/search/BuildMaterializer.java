package bg3builder.search;

import bg3builder.data.Indexes;
import bg3builder.model.Build;
import bg3builder.model.ChoiceList;
import bg3builder.model.ClassProgression;
import bg3builder.model.ClassProgression.Choice;
import bg3builder.model.ClassProgression.LevelEntry;
import bg3builder.model.ClassProgression.PoolReference;
import bg3builder.model.Feature;
import bg3builder.model.SubclassProgression;
import bg3builder.search.BuildPlan.GrantedFeature;
import bg3builder.search.BuildPlan.LevelStep;
import bg3builder.search.BuildPlan.MeaningfulChoice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Converts a Build (an aggregated class-level multiset) into a level-by-level
 * BuildPlan. Walks the class progressions in a chosen order, tracks which
 * grants and choices are meaningful with respect to the user's targets.
 *
 * <h3>Ordering</h3>
 * The build's class composition doesn't itself encode acquisition order.
 * For materialization we pick a canonical order: the starting class is
 * taken to its full count first, then other classes in alphabetical order
 * to their full counts. Subclass picks happen at the subclass's
 * available_at_level for each class.
 *
 * <h3>Meaningful choices</h3>
 * A choice is "meaningful" if (a) at least one of its options is a user
 * target, or (b) it's a subclass pick. Other choices ("pick a feat at L4")
 * are omitted — the user can pick anything.
 *
 * <h3>Subclass selection</h3>
 * For each class in the build, materialization picks ONE subclass to
 * recommend in the output. Selection rule: prefer the subclass that grants
 * the most user-target features. Tie-break alphabetically.
 */
public class BuildMaterializer {

    private final Map<String, Feature> features;
    private final Map<String, ClassProgression> classes;
    private final Map<String, List<SubclassProgression>> subclassesByParent;
    private final ChoiceList pools;
    private final Indexes indexes;

    public BuildMaterializer(
            Map<String, Feature> features,
            Map<String, ClassProgression> classes,
            Map<String, SubclassProgression> subclasses,
            ChoiceList pools,
            Indexes indexes
    ) {
        this.features = features;
        this.classes = classes;
        this.pools = pools;
        this.indexes = indexes;
        this.subclassesByParent = new HashMap<>();
        for (SubclassProgression s : subclasses.values()) {
            subclassesByParent.computeIfAbsent(s.parentClass(), k -> new ArrayList<>()).add(s);
        }
    }

    /**
     * Builds a level-by-level plan for the given build, marking grants and
     * choices that touch the target set.
     */
    public BuildPlan materialize(Build build, Set<String> targets,
                                 Set<String> reachableFeatures,
                                 long searchTimeMs, long nodesExplored) {
        if (build == null) {
            return new BuildPlan(
                    false, 0, null, Map.of(), Map.of(), List.of(),
                    List.copyOf(targets), List.of(), List.copyOf(targets),
                    searchTimeMs, nodesExplored
            );
        }

        // Pick a representative subclass for each class in the build
        Map<String, String> subclassPicks = pickSubclasses(build, targets);

        // Determine ordering: starting class first (full count), then others alphabetically
        List<String> classOrder = orderClasses(build);

        // Walk the levels in order
        List<LevelStep> progression = new ArrayList<>();
        int playerLevel = 1;
        for (String classId : classOrder) {
            int count = build.classLevels().get(classId);
            for (int classLevel = 1; classLevel <= count; classLevel++) {
                LevelStep step = buildLevelStep(
                        playerLevel, classId, classLevel,
                        classId.equals(build.startingClass()),
                        build, targets, subclassPicks
                );
                progression.add(step);
                playerLevel++;
            }
        }

        // Compute satisfied vs unsatisfied
        List<String> satisfied = new ArrayList<>();
        List<String> unsatisfied = new ArrayList<>();
        for (String t : targets) {
            (reachableFeatures.contains(t) ? satisfied : unsatisfied).add(t);
        }

        return new BuildPlan(
                true,
                build.totalLevels(),
                build.startingClass(),
                new TreeMap<>(build.classLevels()),
                new TreeMap<>(subclassPicks),
                progression,
                List.copyOf(targets),
                satisfied,
                unsatisfied,
                searchTimeMs,
                nodesExplored
        );
    }

    /**
     * For each class in the build, picks the subclass that grants the most
     * target features. If no targets are subclass-locked, picks alphabetically.
     */
    private Map<String, String> pickSubclasses(Build build, Set<String> targets) {
        Map<String, String> result = new HashMap<>();
        for (String classId : build.classLevels().keySet()) {
            int classLevel = build.classLevels().get(classId);
            List<SubclassProgression> subs = subclassesByParent.getOrDefault(classId, List.of());

            String best = null;
            int bestScore = -1;
            for (SubclassProgression sub : subs) {
                if (classLevel < sub.availableAtLevel()) continue;
                int score = countTargetReachInSubclass(sub, classLevel, targets);
                if (score > bestScore || (score == bestScore && best != null
                        && sub.id().compareTo(best) < 0)) {
                    bestScore = score;
                    best = sub.id();
                }
            }
            if (best != null) result.put(classId, best);
        }
        return result;
    }

    private int countTargetReachInSubclass(SubclassProgression sub, int classLevel,
                                           Set<String> targets) {
        Set<String> reach = new HashSet<>();
        for (int lvl = 1; lvl <= classLevel; lvl++) {
            LevelEntry lev = sub.levelEntry(lvl);
            if (lev == null) continue;
            for (String id : lev.grants()) reach.add(id);
            for (PoolReference ref : lev.grantsPool()) {
                reach.addAll(poolMembers(ref.fromList(), ref.filter()));
            }
            for (Choice c : lev.choices()) {
                if (c.fromList() != null) {
                    reach.addAll(poolMembers(c.fromList(), c.filter()));
                }
            }
        }
        int count = 0;
        for (String t : targets) if (reach.contains(t)) count++;
        return count;
    }

    /** Starting class first, then others alphabetically. */
    private List<String> orderClasses(Build build) {
        List<String> all = new ArrayList<>(build.classLevels().keySet());
        all.remove(build.startingClass());
        all.sort(String::compareTo);
        all.add(0, build.startingClass());
        return all;
    }

    /**
     * Builds one LevelStep — what's granted at this class level, plus any
     * meaningful choices the user should make.
     */
    private LevelStep buildLevelStep(int playerLevel, String classId, int classLevel,
                                     boolean isStartingClass,
                                     Build build, Set<String> targets,
                                     Map<String, String> subclassPicks) {
        ClassProgression cls = classes.get(classId);
        LevelEntry lev = cls.levelEntry(classLevel);
        boolean isFirstLevelOfStartingClass = isStartingClass && classLevel == 1;

        List<GrantedFeature> grants = new ArrayList<>();
        List<MeaningfulChoice> choices = new ArrayList<>();
        String subclassPicked = null;

        if (lev != null) {
            // Base class grants
            for (String id : lev.grants()) {
                if (id.startsWith("_TODO")) continue;
                grants.add(toGranted(id, targets, null));
            }
            // Base class grants_pool (we don't unfold these in output — user has access to all)
            for (PoolReference ref : lev.grantsPool()) {
                List<String> members = poolMembers(ref.fromList(), ref.filter());
                for (String id : members) {
                    if (targets.contains(id)) {
                        grants.add(toGranted(id, targets, null));
                    }
                }
            }
            // Base class choices
            for (Choice c : lev.choices()) {
                processChoice(c, classId, targets, subclassPicks, choices, grants);
            }
        }

        // Starting class bonus, applied only at level 1 of the starting class
        if (isFirstLevelOfStartingClass && cls.startingClassBonus() != null) {
            for (String id : cls.startingClassBonus().grants()) {
                if (id.startsWith("_TODO")) continue;
                grants.add(toGranted(id, targets, null));
            }
            for (Choice c : cls.startingClassBonus().choices()) {
                processChoice(c, classId, targets, subclassPicks, choices, grants);
            }
        }

        // Subclass contributions for this class level (only if a subclass was picked)
        String chosenSub = subclassPicks.get(classId);
        if (chosenSub != null) {
            SubclassProgression sub = findSubclass(chosenSub);
            if (sub != null && classLevel >= sub.availableAtLevel()) {
                LevelEntry subLev = sub.levelEntry(classLevel);
                if (subLev != null) {
                    for (String id : subLev.grants()) {
                        if (id.startsWith("_TODO")) continue;
                        grants.add(toGranted(id, targets, sub.id()));
                    }
                    for (PoolReference ref : subLev.grantsPool()) {
                        for (String id : poolMembers(ref.fromList(), ref.filter())) {
                            if (targets.contains(id)) {
                                grants.add(toGranted(id, targets, sub.id()));
                            }
                        }
                    }
                    for (Choice c : subLev.choices()) {
                        processChoice(c, classId, targets, subclassPicks, choices, grants);
                    }
                }
                if (classLevel == sub.availableAtLevel()) {
                    subclassPicked = sub.id();
                }
            }
        }

        return new LevelStep(playerLevel, classId, classLevel,
                isFirstLevelOfStartingClass, subclassPicked, grants, choices);
    }

    /**
     * Processes a Choice. Adds to choices if meaningful (target-touching or
     * subclass pick). Does not add to choices for non-target choices.
     */
    private void processChoice(Choice c, String classId, Set<String> targets,
                               Map<String, String> subclassPicks,
                               List<MeaningfulChoice> choices,
                               List<GrantedFeature> grants) {
        if (c.fromList() == null) return;

        // Subclass pick? (heuristic: the pool name ends with "_subclasses")
        if (c.fromList().endsWith("_subclasses")) {
            String picked = subclassPicks.get(classId);
            choices.add(new MeaningfulChoice(
                    c.fromList(),
                    c.filter(),
                    pickCount(c),
                    picked == null ? List.of() : List.of(picked),
                    picked == null ? "subclass pick - choose any" : "subclass pick"
            ));
            return;
        }

        // Otherwise: is any option a target?
        List<String> matchingTargets = new ArrayList<>();
        for (String id : poolMembers(c.fromList(), c.filter())) {
            if (targets.contains(id)) matchingTargets.add(id);
        }

        if (!matchingTargets.isEmpty()) {
            choices.add(new MeaningfulChoice(
                    c.fromList(),
                    c.filter(),
                    pickCount(c),
                    matchingTargets,
                    "matches target(s)"
            ));
        }
        // else: pure "any feat / any spell" choice — omitted from output
    }

    private SubclassProgression findSubclass(String id) {
        for (List<SubclassProgression> list : subclassesByParent.values()) {
            for (SubclassProgression s : list) {
                if (s.id().equals(id)) return s;
            }
        }
        return null;
    }

    private GrantedFeature toGranted(String id, Set<String> targets, String fromSub) {
        Feature f = features.get(id);
        String name = f != null ? f.name() : id;
        return new GrantedFeature(id, name, targets.contains(id), fromSub);
    }

    private static int pickCount(Choice c) {
        if (c.pick() instanceof Integer n) return n;
        return 1;
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
        if (exact instanceof Integer n && f.spellLevel() != null && !f.spellLevel().equals(n)) return false;
        Object max = filter.get("spell_level_max");
        if (max instanceof Integer n && f.spellLevel() != null && f.spellLevel() > n) return false;
        Object min = filter.get("spell_level_min");
        if (min instanceof Integer n && f.spellLevel() != null && f.spellLevel() < n) return false;
        return true;
    }
}