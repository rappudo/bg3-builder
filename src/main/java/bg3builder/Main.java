package bg3builder;

import bg3builder.data.DataLoader;
import bg3builder.data.Indexes;
import bg3builder.model.ChoiceList;
import bg3builder.model.ClassProgression;
import bg3builder.model.Feature;
import bg3builder.model.SubclassProgression;
import bg3builder.search.BuildAPI;
import bg3builder.search.BuildExpander;
import bg3builder.search.BuildMaterializer;
import bg3builder.search.FeatureSearch;
import bg3builder.search.TagSearch;

import java.io.File;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        // ---- Load ----
        DataLoader loader = new DataLoader();
        Map<String, Feature> features = loader.loadFeatures(new File("data/features.json"));
        ChoiceList pools = loader.loadChoiceLists(new File("data/choice_lists.json"));
        Map<String, ClassProgression> classes = loader.loadClasses(new File("data/classes.json"));
        Map<String, SubclassProgression> subclasses = loader.loadSubclasses(new File("data/subclasses.json"));

        Indexes indexes = Indexes.build(features, classes, subclasses, pools);
        BuildExpander expander = new BuildExpander(features, classes, subclasses, pools);
        FeatureSearch featureSearch = new FeatureSearch(expander, indexes);
        TagSearch tagSearch = new TagSearch(expander, indexes);
        BuildMaterializer materializer = new BuildMaterializer(features, classes, subclasses, pools, indexes);
        BuildAPI api = new BuildAPI(featureSearch, tagSearch, materializer, expander, indexes);

        System.out.println("Loaded " + features.size() + " features, "
                + pools.all().size() + " pools, "
                + classes.size() + " classes, "
                + subclasses.size() + " subclasses.");
        System.out.println("Indexes: " + indexes.featuresWithSources()
                + " features have sources, " + indexes.distinctTags() + " tags, "
                + indexes.orphanFeatures(features).size() + " orphans.\n");

        // ============================================================
        // FEATURE SEARCH (JSON IN, JSON OUT)
        // ============================================================
        System.out.println("==================== FEATURE SEARCH ====================\n");

        runFeature(api, "{ \"features\": [\"fireball\"] }");
        runFeature(api, "{ \"features\": [\"rage\"] }");
        runFeature(api, "{ \"features\": [\"fireball\", \"action_surge\"] }");
        runFeature(api, "{ \"features\": [\"fireball\", \"extra_attack\", \"action_surge\"] }");
        runFeature(api, "{ \"features\": [\"frenzy\"] }");
        runFeature(api, "{ \"features\": [\"heavy_armor_proficiency\", \"fireball\"] }");

        // ============================================================
        // TAG SEARCH (JSON IN, JSON OUT)
        // ============================================================
        System.out.println("\n==================== TAG SEARCH ====================\n");

        runTag(api, "{ \"tags\": [\"fire\", \"damage\"], \"max_levels\": 5, \"top_n\": 2 }");
        runTag(api, "{ \"tags\": [\"healing\"], \"max_levels\": 4, \"top_n\": 2 }");
        runTag(api, "{ \"tags\": [\"action_economy\"], \"max_levels\": 5, \"top_n\": 2 }");
    }

    private static void runFeature(BuildAPI api, String json) throws Exception {
        System.out.println("REQUEST: " + json);
        String response = api.findBuildForFeatures(json);
        System.out.println("RESPONSE:\n" + response + "\n");
        System.out.println("------------------------------------------------------------\n");
    }

    private static void runTag(BuildAPI api, String json) throws Exception {
        System.out.println("REQUEST: " + json);
        String response = api.findBuildsForTags(json);
        System.out.println("RESPONSE:\n" + response + "\n");
        System.out.println("------------------------------------------------------------\n");
    }
}