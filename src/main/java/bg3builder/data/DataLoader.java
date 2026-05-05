package bg3builder.data;

import bg3builder.model.Feature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads JSON data files from disk into in-memory model objects.
 * One instance loads everything once at startup; the resulting maps are
 * passed to the rest of the application as immutable reference data.
 */
public class DataLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Loads features.json. The file is structured as a map of id -> entry,
     * with entries having no "id" field of their own (the key IS the id).
     * We read it as a generic map first, then construct Feature records
     * manually so each gets its id from the map key.
     */
    public Map<String, Feature> loadFeatures(File path) throws IOException {
        // Read raw JSON as a fully-generic map. Values can be either Strings
        // (for _comment entries) or nested Maps (for actual feature entries).
        Map<String, Object> raw = mapper.readValue(
                path,
                new TypeReference<>() {}
        );

        Map<String, Feature> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String id = entry.getKey();

            // Skip comment keys (their values are plain strings, not feature objects)
            if (id.startsWith("_")) continue;

            // Defensive: if a non-underscore key has a non-Map value, something
            // is wrong with the data. Surface it instead of silently skipping.
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

    @SuppressWarnings("unchecked")
    private static java.util.List<String> castList(Object o) {
        return (java.util.List<String>) o;
    }
}