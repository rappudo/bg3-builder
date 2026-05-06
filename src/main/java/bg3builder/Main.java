package bg3builder;

import bg3builder.data.DataLoader;
import bg3builder.data.Indexes;
import bg3builder.model.Build;
import bg3builder.model.ChoiceList;
import bg3builder.model.ClassProgression;
import bg3builder.model.Feature;
import bg3builder.search.BuildExpander;
import bg3builder.search.FeatureSearch;

import java.io.File;
import java.util.Map;
import java.util.Set;

public class Main {
    public static void main(String[] args) throws Exception {
        // ---- Load reference data ----
        DataLoader loader = new DataLoader();
        Map<String, Feature> features    = loader.loadFeatures(new File("data/features.json"));
        ChoiceList pools                  = loader.loadChoiceLists(new File("data/choice_lists.json"));
        Map<String, ClassProgression> classes = loader.loadClasses(new File("data/classes.json"));

        Indexes indexes = Indexes.build(features, classes, pools);
        BuildExpander expander = new BuildExpander(features, classes, Map.of(), pools);
        FeatureSearch search = new FeatureSearch(expander, indexes);

        System.out.println("Loaded " + features.size() + " features, "
                + pools.all().size() + " pools, "
                + classes.size() + " classes.");
        System.out.println("Built indexes: " + indexes.featuresWithSources()
                + " features have sources, " + indexes.distinctTags() + " tags.\n");

        // ============================================================
        // FEATURE SEARCH TESTS
        // ============================================================

        // Simple: one feature, one obvious answer
        runSearch(search, "Just fireball",
                Set.of("fireball"));

        // Simple: one martial feature
        runSearch(search, "Just rage",
                Set.of("rage"));

        // Two features, same class — Wizard 5 should answer this
        runSearch(search, "Fireball + Magic Missile",
                Set.of("fireball", "magic_missile"));

        // Two features that demand different classes — multiclass required
        runSearch(search, "Fireball + Action Surge",
                Set.of("fireball", "action_surge"));

        // Three features, classic "spellblade" combo
        runSearch(search, "Fireball + Extra Attack + Action Surge",
                Set.of("fireball", "extra_attack", "action_surge"));

        // Eldritch Blast + Hex (Warlock 1 should be enough)
        runSearch(search, "Eldritch Blast + Hex",
                Set.of("eldritch_blast", "hex"));

        // Heavy armor must come from starting class — verify it works
        runSearch(search, "Heavy Armor + Fireball",
                Set.of("heavy_armor_proficiency", "fireball"));

        // Lots of features — should still find a solution
        runSearch(search, "Healing-focused build",
                Set.of("cure_wounds", "healing_words", "lay_on_hands"));

        // Should fail: feature that doesn't exist (typo simulation)
        runSearch(search, "Typo'd feature",
                Set.of("frieball"));

        // Hard test: a feature that's only in subclasses (orphan)
        // — should fail until subclasses.json is built.
        runSearch(search, "Frenzy (subclass-locked, expected to fail)",
                Set.of("frenzy"));
    }

    private static void runSearch(FeatureSearch search, String label, Set<String> targets) {
        System.out.println("=== " + label + " ===");
        System.out.println("  Targets: " + targets);
        long t0 = System.nanoTime();
        Build result = search.findSmallestBuild(targets);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  Result:  " + FeatureSearch.describe(result));
        System.out.println("  Time:    " + ms + "ms\n");
    }
}