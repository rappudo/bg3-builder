package bg3builder.data;

import bg3builder.model.ChoiceList;
import bg3builder.model.ClassProgression;
import bg3builder.model.Feature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads JSON data files from disk into in-memory model objects.
 * One instance loads everything once at startup; the resulting maps are
 * passed to the rest of the application as immutable reference data.
 */
public class DataLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    /** features.json: id -> Feature record. */
    public Map<String, Feature> loadFeatures(File path) throws IOException {
        Map<String, Object> raw = mapper.readValue(path, new TypeReference<>() {});

        Map<String, Feature> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String id = entry.getKey();
            if (id.startsWith("_")) continue;

            if (!(entry.getValue() instanceof Map<?, ?> attrsRaw)) {
                throw new IOException("Expected object for feature '" + id
                        + "' but got: " + entry.getValue().getClass().getSimpleName());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) attrsRaw;

            Feature feature = new Feature(
                    id,
                    (String) attrs.get("name"),
                    (String) attrs.get("type"),
                    castList(attrs.get("tags")),
                    castList(attrs.get("classes")),
                    (Integer) attrs.get("spell_level"),
                    (String) attrs.get("school")
            );
            result.put(id, feature);
        }
        return result;
    }

    /** choice_lists.json: pool name -> list of feature ids. */
    public ChoiceList loadChoiceLists(File path) throws IOException {
        Map<String, Object> raw = mapper.readValue(path, new TypeReference<>() {});

        Map<String, List<String>> pools = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String id = entry.getKey();
            if (id.startsWith("_")) continue;

            if (entry.getValue() instanceof List<?> listRaw) {
                @SuppressWarnings("unchecked")
                List<String> typed = (List<String>) listRaw;
                pools.put(id, typed);
            } else {
                throw new IOException("Expected array for pool '" + id
                        + "' but got: " + entry.getValue().getClass().getSimpleName());
            }
        }
        return new ChoiceList(pools);
    }

    /**
     * classes.json: class id -> ClassProgression.
     * Round-trips each entry through Jackson to leverage record binding for
     * the nested LevelEntry / Choice / PoolReference structure, then attaches
     * the id from the surrounding map key.
     */
    public Map<String, ClassProgression> loadClasses(File path) throws IOException {
        Map<String, Object> raw = mapper.readValue(path, new TypeReference<>() {});

        Map<String, ClassProgression> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String id = entry.getKey();
            if (id.startsWith("_")) continue;

            if (!(entry.getValue() instanceof Map<?, ?>)) {
                throw new IOException("Expected object for class '" + id
                        + "' but got: " + entry.getValue().getClass().getSimpleName());
            }

            String json = mapper.writeValueAsString(entry.getValue());
            ClassProgression.Dto dto = mapper.readValue(json, ClassProgression.Dto.class);
            result.put(id, dto.toModel(id));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return (List<String>) o;
    }
}