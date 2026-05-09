package com.ai.code.review.memory;

import com.ai.code.review.model.ReviewResult;
import com.ai.code.review.model.ReviewSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters review results based on false-positive learning data.
 *
 * Applies false-positive decay logic:
 * - If a rule is downgraded, decrease severity one level
 *   (CRITICAL -> MAJOR -> MINOR -> INFO -> stays INFO)
 * - If a rule has been dismissed more times than accepted AND is downgraded,
 *   suppress the result entirely
 * - Non-downgraded rules pass through unchanged
 */
@Component
public class FalsePositiveFilter {

    private static final Logger log = LoggerFactory.getLogger(FalsePositiveFilter.class);

    private final MemoryService memoryService;

    public FalsePositiveFilter(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * Applies false-positive filtering to a list of review results.
     * Returns a new list with filtering applied; the original list is unchanged.
     *
     * @param results the raw review results from agents
     * @return filtered list of review results
     */
    public List<ReviewResult> filter(List<ReviewResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        List<ReviewResult> filtered = new ArrayList<>();

        for (ReviewResult result : results) {
            String agentType = result.agentId();
            String ruleCategory = result.category();

            // Skip results with null/invalid agent type or category
            if (agentType == null || ruleCategory == null || agentType.isBlank() || ruleCategory.isBlank()) {
                filtered.add(result);
                continue;
            }

            // Check if this rule is downgraded
            boolean isDowngraded = memoryService.isRuleDowngraded(agentType, ruleCategory);

            if (!isDowngraded) {
                // Rule is not downgraded — pass through unchanged
                filtered.add(result);
                continue;
            }

            // Rule is downgraded — check stats for suppression decision
            RuleStats stats = memoryService.getRuleStats(agentType, ruleCategory);

            boolean shouldSuppress = false;
            if (stats != null) {
                shouldSuppress = stats.dismissed() > stats.accepted() && isDowngraded;
            }

            if (shouldSuppress) {
                // Suppress: dismissed > accepted AND downgraded
                log.warn("Suppressing result: rule '{}/{}' has been dismissed {} times vs {} accepted "
                        + "(downgraded). Result: {} at {}:{}",
                        agentType, ruleCategory,
                        stats != null ? stats.dismissed() : 0,
                        stats != null ? stats.accepted() : 0,
                        result.title(), result.filePath(), result.lineStart());
                continue; // Skip adding this result
            }

            // Downgraded but not suppressed — decrease severity
            ReviewSeverity newSeverity = decreaseSeverity(result.severity());
            ReviewResult downgradedResult = new ReviewResult(
                    result.agentId(),
                    newSeverity,
                    result.category(),
                    result.filePath(),
                    result.lineStart(),
                    result.lineEnd(),
                    result.title(),
                    result.description() + " [auto-downgraded due to historical false-positive rate]",
                    result.suggestion()
            );

            log.info("Downgraded result severity from {} to {} for rule '{}/{}': {} at {}:{}",
                    result.severity(), newSeverity, agentType, ruleCategory,
                    result.title(), result.filePath(), result.lineStart());

            filtered.add(downgradedResult);
        }

        log.debug("FalsePositiveFilter: {} results in, {} results out", results.size(), filtered.size());
        return filtered;
    }

    /**
     * Decreases severity by one level.
     * CRITICAL -> MAJOR -> MINOR -> INFO -> INFO (stays INFO)
     */
    private ReviewSeverity decreaseSeverity(ReviewSeverity severity) {
        return switch (severity) {
            case CRITICAL -> ReviewSeverity.MAJOR;
            case MAJOR -> ReviewSeverity.MINOR;
            case MINOR -> ReviewSeverity.INFO;
            case INFO -> ReviewSeverity.INFO;
        };
    }
}
