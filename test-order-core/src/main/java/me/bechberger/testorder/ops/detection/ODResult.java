package me.bechberger.testorder.ops.detection;

import java.util.List;

/**
 * Represents a confirmed order-dependent test result.
 *
 * @param victim the test that fails or needs help
 * @param type the OD pattern type
 * @param dependencyChain the minimal sequence exhibiting the dependency
 * @param description human-readable explanation
 * @param confidence 0.0–1.0 confidence in this result
 */
public record ODResult(
        String victim,
        ODType type,
        List<String> dependencyChain,
        String description,
        double confidence) {

    public ODResult(String victim, ODType type, List<String> dependencyChain, String description) {
        this(victim, type, dependencyChain, description, 1.0);
    }

    /** The polluter (for VICTIM type) or setter (for BRITTLE type). */
    public String polluter() {
        return dependencyChain.isEmpty() ? null : dependencyChain.get(0);
    }

    /** Alias for polluter — the state-setter for BRITTLE type. */
    public String stateSetter() {
        return polluter();
    }
}
