package com.ai.code.review.report;

import com.ai.code.review.model.AggregatedReport;
import com.ai.code.review.model.ReviewResult;
import com.ai.code.review.model.ReviewSeverity;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates Markdown-formatted review reports from aggregated results.
 *
 * Produces structured reports with summary counts, per-file findings,
 * and agent contribution breakdown for posting to GitHub PRs.
 */
@Component
public class ReportGenerator {

    private static final Map<ReviewSeverity, String> SEVERITY_BADGES = Map.of(
            ReviewSeverity.CRITICAL, ":red_circle: CRITICAL",
            ReviewSeverity.MAJOR, ":orange_circle: MAJOR",
            ReviewSeverity.MINOR, ":yellow_circle: MINOR",
            ReviewSeverity.INFO, ":information_source: INFO"
    );

    private static final Map<ReviewSeverity, String> SEVERITY_EMOJIS = Map.of(
            ReviewSeverity.CRITICAL, ":red_circle:",
            ReviewSeverity.MAJOR, ":orange_circle:",
            ReviewSeverity.MINOR, ":yellow_circle:",
            ReviewSeverity.INFO, ":information_source:"
    );

    /**
     * Generates a complete Markdown report from an AggregatedReport.
     *
     * @param report the aggregated report containing all review findings
     * @return a Markdown-formatted string suitable for posting as a PR comment
     */
    public String generateMarkdown(AggregatedReport report) {
        if (report == null) {
            return "## AI Code Review Report\n\nNo data available.";
        }

        StringBuilder md = new StringBuilder();

        // Header
        md.append("## AI Code Review Report for PR ").append(escapeMarkdown(report.prId())).append("\n\n");

        // Summary section
        appendSummary(md, report);

        // Findings by file
        appendFindingsByFile(md, report);

        // Agent summary
        appendAgentSummary(md, report);

        return md.toString();
    }

    /**
     * Appends the summary section with severity counts in a table.
     */
    private void appendSummary(StringBuilder md, AggregatedReport report) {
        md.append("### Summary\n\n");
        md.append("| Severity | Count |\n");
        md.append("|----------|-------|\n");

        md.append("| ").append(SEVERITY_EMOJIS.get(ReviewSeverity.CRITICAL)).append(" Critical | ")
                .append(report.criticalCount()).append(" |\n");
        md.append("| ").append(SEVERITY_EMOJIS.get(ReviewSeverity.MAJOR)).append(" Major | ")
                .append(report.majorCount()).append(" |\n");
        md.append("| ").append(SEVERITY_EMOJIS.get(ReviewSeverity.MINOR)).append(" Minor | ")
                .append(report.minorCount()).append(" |\n");

        // Count INFO from the results list
        long infoCount = report.results().stream()
                .filter(r -> r.severity() == ReviewSeverity.INFO)
                .count();
        md.append("| ").append(SEVERITY_EMOJIS.get(ReviewSeverity.INFO)).append(" Info | ")
                .append(infoCount).append(" |\n");

        md.append("\n").append(escapeMarkdown(report.summary())).append("\n\n");
    }

    /**
     * Appends per-file findings sections with issue tables.
     */
    private void appendFindingsByFile(StringBuilder md, AggregatedReport report) {
        if (report.results().isEmpty()) {
            md.append("### Findings\n\nNo issues found. :white_check_mark:\n\n");
            return;
        }

        md.append("### Findings by File\n\n");

        // Group results by filePath
        Map<String, List<ReviewResult>> byFile = report.results().stream()
                .collect(Collectors.groupingBy(
                        r -> r.filePath() != null ? r.filePath() : "(unknown)",
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        for (Map.Entry<String, List<ReviewResult>> entry : byFile.entrySet()) {
            String filePath = entry.getKey();
            List<ReviewResult> fileResults = entry.getValue();

            md.append("#### ").append(escapeMarkdown(filePath)).append("\n\n");
            md.append("| Line | Severity | Category | Description |\n");
            md.append("|------|----------|----------|-------------|\n");

            for (ReviewResult result : fileResults) {
                String lineRange = result.lineStart() > 0
                        ? (result.lineEnd() > result.lineStart()
                        ? result.lineStart() + "-" + result.lineEnd()
                        : String.valueOf(result.lineStart()))
                        : "-";

                String severityBadge = SEVERITY_BADGES.getOrDefault(result.severity(), "UNKNOWN");
                String category = result.category() != null ? result.category() : "-";
                String description = result.description() != null
                        ? result.description().replace("\n", " ").trim()
                        : "-";

                md.append("| ").append(lineRange).append(" | ")
                        .append(severityBadge).append(" | ")
                        .append(escapeMarkdown(category)).append(" | ")
                        .append(escapeMarkdown(description)).append(" |\n");
            }
            md.append("\n");
        }
    }

    /**
     * Appends the agent contribution summary.
     */
    private void appendAgentSummary(StringBuilder md, AggregatedReport report) {
        if (report.results().isEmpty()) {
            return;
        }

        md.append("### Agent Summary\n\n");

        // Count findings per agent
        Map<String, Long> byAgent = report.results().stream()
                .collect(Collectors.groupingBy(
                        r -> r.agentId() != null ? r.agentId() : "unknown",
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        for (Map.Entry<String, Long> entry : byAgent.entrySet()) {
            md.append("- **").append(escapeMarkdown(entry.getKey())).append("**")
                    .append(": ").append(entry.getValue())
                    .append(" finding").append(entry.getValue() != 1 ? "s" : "")
                    .append("\n");
        }
    }

    /**
     * Escapes special Markdown characters to prevent rendering issues.
     */
    private String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("`", "\\`");
    }
}
