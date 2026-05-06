package bg3builder.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The contents of choice_lists.json: named pools of feature ids.
 * Each pool key (e.g. "spells_wizard", "feats", "fighter_subclasses") maps
 * to a list of feature ids that can be picked from it.
 *
 * <p>Comment keys (starting with "_") are stripped during loading.
 */
public final class ChoiceList {

    private final Map<String, List<String>> pools;

    public ChoiceList(Map<String, List<String>> pools) {
        this.pools = Map.copyOf(pools);
    }

    /** Returns the contents of a pool by name, or empty list if missing. */
    public List<String> get(String poolName) {
        return pools.getOrDefault(poolName, Collections.emptyList());
    }

    /** True if a pool with this name exists. */
    public boolean has(String poolName) {
        return pools.containsKey(poolName);
    }

    public Map<String, List<String>> all() {
        return pools;
    }
}