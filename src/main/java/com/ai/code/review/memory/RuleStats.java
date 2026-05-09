package com.ai.code.review.memory;

/**
 * Record for rule-level statistics tracking.
 *
 * Maintains counters for how many times a specific rule (identified by
 * ruleCategory + agentType) has been accepted vs dismissed. The dismissRatio
 * drives the false-positive decay algorithm: when dismissRatio exceeds a
 * configurable threshold, the rule is considered downgraded.
 */
public record RuleStats(
    String ruleCategory,
    String agentType,
    int total,
    int accepted,
    int dismissed,
    double dismissRatio,
    boolean isDowngraded
) {

    /**
     * Creates RuleStats from raw counters, computing derived fields.
     */
    public static RuleStats fromCounts(String ruleCategory, String agentType,
                                       int total, int accepted, int dismissed,
                                       boolean isDowngraded) {
        double ratio = total > 0 ? (double) dismissed / total : 0.0;
        return new RuleStats(ruleCategory, agentType, total, accepted, dismissed, ratio, isDowngraded);
    }
}
