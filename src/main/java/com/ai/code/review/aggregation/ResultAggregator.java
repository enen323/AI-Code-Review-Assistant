package com.ai.code.review.aggregation;

import com.ai.code.review.model.AggregatedReport;
import com.ai.code.review.model.ReviewResult;
import com.ai.code.review.model.ReviewSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Aggregates, deduplicates, and sorts review results from multiple agents.
 *
 * Produces an AggregatedReport with severity counts, summary text,
 * and results sorted by severity, file, and line number.
 */
@Component
public class ResultAggregator {

    private static final Logger log = LoggerFactory.getLogger(ResultAggregator.class);

    /**
     * Aggregates raw review results into a structured report.
     *
     * @param rawResults the list of raw results from all agents
     * @param prId       the pull request identifier
     * @return aggregated report with deduplicated, sorted results
     */
    public AggregatedReport aggregate(List<ReviewResult> rawResults, String prId) {
        if (rawResults == null || rawResults.isEmpty()) {
            log.info("No results to aggregate for PR {}", prId);
            return new AggregatedReport(prId, "No issues found.", 0, 0, 0, List.of());
        }

        // Step 1: Deduplicate using (filePath, lineStart, lineEnd, category) as composite key.
        // Keep the entry with the highest severity (lowest ordinal) when duplicates are found.
        Map<String, ReviewResult> deduped = new LinkedHashMap<>();
        for (ReviewResult result : rawResults) {
            String key = compositeKey(result);
            ReviewResult existing = deduped.get(key);
            if (existing == null || result.severity().ordinal() < existing.severity().ordinal()) {
                deduped.put(key, result);
            }
        }

        List<ReviewResult> uniqueResults = new ArrayList<>(deduped.values());

        // Step 2: Sort — CRITICAL > MAJOR > MINOR > INFO, then by filePath, then by lineStart
        uniqueResults.sort(Comparator
                .comparingInt((ReviewResult r) -> r.severity().ordinal())
                .thenComparing(ReviewResult::filePath, Comparator.nullsLast(String::compareTo))
                .thenComparingInt(ReviewResult::lineStart)
        );

        // Step 3: Count severities
        long criticalCount = countBySeverity(uniqueResults, ReviewSeverity.CRITICAL);
        long majorCount = countBySeverity(uniqueResults, ReviewSeverity.MAJOR);
        long minorCount = countBySeverity(uniqueResults, ReviewSeverity.MINOR);

        // Step 4: Count distinct files
        long fileCount = uniqueResults.stream()
                .map(ReviewResult::filePath)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        // Step 5: Generate summary
        String summary = generateSummary(uniqueResults.size(), criticalCount, majorCount, minorCount, fileCount);

        return new AggregatedReport(
                prId,
                summary,
                (int) criticalCount,
                (int) majorCount,
                (int) minorCount,
                List.copyOf(uniqueResults)
        );
    }

    /**
     * Builds a composite deduplication key from result fields.
     */
    private String compositeKey(ReviewResult result) {
        return (result.filePath() != null ? result.filePath() : "") + "|"
                + result.lineStart() + "|"
                + result.lineEnd() + "|"
                + (result.category() != null ? result.category() : "");
    }

    /**
     * Counts results with the given severity.
     */
    private long countBySeverity(List<ReviewResult> results, ReviewSeverity severity) {
        return results.stream()
                .filter(r -> r.severity() == severity)
                .count();
    }

    /**
     * Generates a human-readable summary string.
     */
    private String generateSummary(int total, long critical, long major, long minor, long files) {
        StringBuilder sb = new StringBuilder("Found ");
        sb.append(critical).append(" critical, ");
        sb.append(major).append(" major, ");
        sb.append(minor).append(" minor issues");
        if (total > 0) {
            sb.append(" across ").append(files).append(" file").append(files != 1 ? "s" : "");
        }
        sb.append(".");
        return sb.toString();
    }
}
