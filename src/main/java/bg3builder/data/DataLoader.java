package bg3builder.data;

import bg3builder.model.ChoiceList;
import bg3builder.model.ClassProgression;
import bg3builder.model.Feature;
import bg3builder.model.SubclassProgression;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads JSON data files from disk into in-memory model objects.
 */
public class DataLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Feature> loadFeatures(File path) throws IOException {
        Map<String, Object> raw = mapper.readValue(path, new TypeReference<>() {});
        Map<String, Feature> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String id = entry.getKey();
            if (id.startsWith("_")) continue;

            if (!(entry.getValue() instanceof Map<?, ?> attrsRaw)) continue;
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
            }
        }
        return new ChoiceList(pools);
    }

    public Map<String, ClassProgression> loadClasses(File path) throws IOException {
        Map<String, Object> raw = mapper.readValue(path, new TypeReference<>() {});
        Map<String, ClassProgression> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String id = entry.getKey();
            if (id.startsWith("_")) continue;
            if (!(entry.getValue() instanceof Map<?, ?>)) continue;

            String json = mapper.writeValueAsString(entry.getValue());
            ClassProgression.Dto dto = mapper.readValue(json, ClassProgression.Dto.class);
            result.put(id, dto.toModel(id));
        }
        return result;
    }

    /**
     * Loads subclasses.json. Same round-trip-through-Jackson trick as classes —
     * each entry is parsed into a Dto, then converted to a SubclassProgression
     * with the id from the surrounding map key.
     */
    public Map<String, SubclassProgression> loadSubclasses(File path) throws IOException {
        Map<String, Object> raw = mapper.readValue(path, new TypeReference<>() {});
        Map<String, SubclassProgression> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String id = entry.getKey();
            if (id.startsWith("_")) continue;
            if (!(entry.getValue() instanceof Map<?, ?>)) continue;

            String json = mapper.writeValueAsString(entry.getValue());
            SubclassProgression.Dto dto = mapper.readValue(json, SubclassProgression.Dto.class);
            result.put(id, dto.toModel(id));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return (List<String>) o;
    }
}