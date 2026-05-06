package bg3builder.model;

import java.util.HashMap;
import java.util.Map;

/**
 * A character build expressed as an aggregated class-level map.
 *
 * <p>Order of acquisition is not tracked — for the build-search algorithm,
 * what matters is the final composition. The single ordering fact that does
 * matter (which class was first) is captured by {@link #startingClass()}
 * because of the starting_class_bonus rule.
 *
 * <p>Multiclass picks per class (subclass id) are kept in their own map.
 *
 * @param classLevels      class id -> levels taken in that class (always >= 1)
 * @param startingClass    which class was taken first (drives starting_class_bonus)
 * @param subclasses       class id -> chosen subclass id (only for classes whose
 *                         level reached the subclass-pick level)
 */
public record Build(
        Map<String, Integer> classLevels,
        String startingClass,
        Map<String, String> subclasses
) {

    public Build {
        classLevels = Map.copyOf(classLevels);
        subclasses = Map.copyOf(subclasses);
    }

    /** Total number of class levels in this build. */
    public int totalLevels() {
        return classLevels.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Convenience constructor for a single-class build. */
    public static Build singleClass(String classId, int levels) {
        return new Build(
                Map.of(classId, levels),
                classId,
                Map.of()
        );
    }

    /** Builder for tests and search expansion. Mutable; build() returns immutable Build. */
    public static class Builder {
        private final Map<String, Integer> classLevels = new HashMap<>();
        private String startingClass;
        private final Map<String, String> subclasses = new HashMap<>();

        public Builder addLevels(String classId, int n) {
            classLevels.merge(classId, n, Integer::sum);
            if (startingClass == null) startingClass = classId;
            return this;
        }

        public Builder withSubclass(String classId, String subclassId) {
            subclasses.put(classId, subclassId);
            return this;
        }

        public Builder withStartingClass(String classId) {
            this.startingClass = classId;
            return this;
        }

        public Build build() {
            return new Build(classLevels, startingClass, subclasses);
        }
    }
}