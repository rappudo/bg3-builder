package bg3builder.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import bg3builder.data.Indexes;
import bg3builder.model.Build;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * High-level entry points for clients (CLI, web frontend, etc.).
 * Each method takes a JSON input describing the user's request and returns
 * a JSON output describing the recommended build with level-by-level details.
 *
 * <p>Internally each method orchestrates: parse JSON → run search →
 * materialize Build to BuildPlan → serialize BuildPlan to JSON.
 */
public class BuildAPI {

    private final FeatureSearch featureSearch;
    private final TagSearch tagSearch;
    private final BuildMaterializer materializer;
    private final BuildExpander expander;
    private final Indexes indexes;
    private final ObjectMapper jsonMapper;

    public BuildAPI(FeatureSearch featureSearch, TagSearch tagSearch,
                    BuildMaterializer materializer, BuildExpander expander,
                    Indexes indexes) {
        this.featureSearch = featureSearch;
        this.tagSearch = tagSearch;
        this.materializer = materializer;
        this.expander = expander;
        this.indexes = indexes;
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // Convert camelCase Java fields to snake_case JSON output
        this.jsonMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    /**
     * Input: {"features": ["fireball", "extra_attack"]}
     * Output: a BuildPlan as JSON.
     */
    public String findBuildForFeatures(String inputJson) throws Exception {
        FeatureRequest req = jsonMapper.readValue(inputJson, FeatureRequest.class);
        return findBuildForFeatures(req.features() == null ? List.of() : req.features());
    }

    /**
     * Convenience overload taking a list directly.
     */
    public String findBuildForFeatures(List<String> features) throws Exception {
        Set<String> targets = new HashSet<>(features);
        long t0 = System.nanoTime();
        Build result = featureSearch.findSmallestBuild(targets);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long nodes = featureSearch.lastNodesExplored();

        Set<String> reachable = result == null ? Set.of() : expander.reachableFeatures(result);
        BuildPlan plan = materializer.materialize(result, targets, reachable, ms, nodes);
        return jsonMapper.writeValueAsString(plan);
    }

    /**
     * Input: {"tags": ["fire", "damage"], "max_levels": 5, "top_n": 3}
     * Output: an array of BuildPlans, ordered by score DESC.
     */
    public String findBuildsForTags(String inputJson) throws Exception {
        TagRequest req = jsonMapper.readValue(inputJson, TagRequest.class);
        return findBuildsForTags(
                req.tags() == null ? List.of() : req.tags(),
                req.maxLevels() == 0 ? 5 : req.maxLevels(),
                req.topN() == 0 ? 3 : req.topN()
        );
    }

    public String findBuildsForTags(List<String> tags, int maxLevels, int topN) throws Exception {
        Set<String> tagSet = new HashSet<>(tags);
        long t0 = System.nanoTime();
        List<TagSearch.ScoredBuild> results = tagSearch.findBestBuilds(tagSet, maxLevels, topN);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long evaluated = tagSearch.lastBuildsEvaluated();

        List<TagSearchResult> resultPlans = new java.util.ArrayList<>();
        for (TagSearch.ScoredBuild sb : results) {
            // For tag-based output, "targets" are the matched-features set
            Set<String> reachable = expander.reachableFeatures(sb.build());
            BuildPlan plan = materializer.materialize(
                    sb.build(), sb.matchedFeatures(), reachable, ms, evaluated);
            resultPlans.add(new TagSearchResult(sb.score(), plan));
        }

        TagSearchResponse resp = new TagSearchResponse(
                tags, maxLevels, ms, evaluated, resultPlans);
        return jsonMapper.writeValueAsString(resp);
    }

    // ------ JSON request/response shapes ------

    public record FeatureRequest(List<String> features) {}
    public record TagRequest(List<String> tags, int maxLevels, int topN) {}
    public record TagSearchResult(int tagsCovered, BuildPlan plan) {}
    public record TagSearchResponse(
            List<String> tags,
            int maxLevels,
            long searchTimeMs,
            long buildsEvaluated,
            List<TagSearchResult> results
    ) {}
}