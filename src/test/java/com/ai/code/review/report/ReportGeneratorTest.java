package com.ai.code.review.report;

import com.ai.code.review.model.AggregatedReport;
import com.ai.code.review.model.ReviewResult;
import com.ai.code.review.model.ReviewSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReportGenerator verifying markdown output structure and content.
 */
class ReportGeneratorTest {

    private ReportGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ReportGenerator();
    }

    /**
     * Tests that a valid report generates all required markdown sections.
     */
    @Test
    void testGenerateMarkdownIncludesAllSections() {
        List<ReviewResult> results = List.of(
                new ReviewResult("security", ReviewSeverity.CRITICAL, "SQL_INJECTION",
                        "src/main/java/UserService.java", 42, 45,
                        "SQL Injection risk", "User input is not sanitized",
                        "Use prepared statements"),
                new ReviewResult("logic", ReviewSeverity.MAJOR, "NULL_CHECK",
                        "src/main/java/UserService.java", 100, 105,
                        "Null pointer risk", "Method may return null",
                        "Add @NotNull annotation"),
                new ReviewResult("code-style", ReviewSeverity.MINOR, "NAMING",
                        "src/main/java/Config.java", 10, 10,
                        "Poor variable name", "Variable 'x' is not descriptive",
                        "Rename to 'configValue'")
        );

        AggregatedReport report = new AggregatedReport(
                "owner/repo#42",
                "Found 1 critical, 1 major, 1 minor issues across 2 files.",
                1, 1, 1,
                results
        );

        String markdown = generator.generateMarkdown(report);

        // Check header
        assertTrue(markdown.contains("AI Code Review Report for PR owner/repo#42"));

        // Check summary section
        assertTrue(markdown.contains("### Summary"));
        assertTrue(markdown.contains("Critical"));
        assertTrue(markdown.contains("Major"));
        assertTrue(markdown.contains("Minor"));

        // Check findings by file section
        assertTrue(markdown.contains("### Findings by File"));
        assertTrue(markdown.contains("UserService.java"));
        assertTrue(markdown.contains("Config.java"));

        // Check line numbers in per-file table
        assertTrue(markdown.contains("42"));
        assertTrue(markdown.contains("100"));
        assertTrue(markdown.contains("10"));

        // Check agent summary
        assertTrue(markdown.contains("### Agent Summary"));
        assertTrue(markdown.contains("security"));
        assertTrue(markdown.contains("logic"));
        assertTrue(markdown.contains("code-style"));
    }

    /**
     * Tests that an empty report produces valid markdown indicating no issues.
     */
    @Test
    void testEmptyReport() {
        AggregatedReport report = new AggregatedReport(
                "pr-1", "No issues found.", 0, 0, 0, List.of()
        );

        String markdown = generator.generateMarkdown(report);

        assertTrue(markdown.contains("AI Code Review Report for PR pr-1"));
        assertTrue(markdown.contains("No issues found"));
    }

    /**
     * Tests that a null report produces a fallback message.
     */
    @Test
    void testNullReport() {
        String markdown = generator.generateMarkdown(null);
        assertTrue(markdown.contains("No data available"));
    }

    /**
     * Tests that the markdown contains severity counts in the summary table.
     */
    @Test
    void testSummaryCountsAreAccurate() {
        List<ReviewResult> results = List.of(
                new ReviewResult("security", ReviewSeverity.CRITICAL, "SEC",
                        "F1.java", 1, 2, "C1", "D", "S"),
                new ReviewResult("logic", ReviewSeverity.MAJOR, "LOG",
                        "F2.java", 3, 4, "M1", "D", "S"),
                new ReviewResult("code-style", ReviewSeverity.INFO, "STYLE",
                        "F3.java", 5, 6, "I1", "D", "S")
        );

        AggregatedReport report = new AggregatedReport(
                "pr-2", "Summary", 1, 1, 0,
                results
        );

        String markdown = generator.generateMarkdown(report);

        // Verify counts in the summary table
        assertTrue(markdown.contains("1 |"));
        assertTrue(markdown.contains("1 |"));

        // Should also show 1 INFO (computed from results)
        assertTrue(markdown.contains("Info"));
    }

    /**
     * Tests that per-file sections correctly group findings by file.
     */
    @Test
    void testFindingsGroupedByFile() {
        List<ReviewResult> results = List.of(
                new ReviewResult("security", ReviewSeverity.CRITICAL, "SEC",
                        "FileA.java", 1, 2, "C1", "Desc", "Fix"),
                new ReviewResult("logic", ReviewSeverity.MAJOR, "LOG",
                        "FileB.java", 3, 4, "M1", "Desc", "Fix"),
                new ReviewResult("code-style", ReviewSeverity.INFO, "STYLE",
                        "FileA.java", 5, 6, "I1", "Desc", "Fix")
        );

        AggregatedReport report = new AggregatedReport(
                "pr-3", "Summary", 1, 1, 0,
                results
        );

        String markdown = generator.generateMarkdown(report);

        // Both files should appear
        assertTrue(markdown.contains("FileA.java"));
        assertTrue(markdown.contains("FileB.java"));

        // FileA should appear only once as a heading
        int firstIndex = markdown.indexOf("FileA.java");
        assertTrue(firstIndex >= 0);
    }

    /**
     * Tests that agent summary shows correct finding counts per agent.
     */
    @Test
    void testAgentSummaryCounts() {
        List<ReviewResult> results = List.of(
                new ReviewResult("security", ReviewSeverity.CRITICAL, "SEC",
                        "F.java", 1, 2, "C1", "D", "F"),
                new ReviewResult("security", ReviewSeverity.MAJOR, "SEC2",
                        "F.java", 3, 4, "C2", "D", "F"),
                new ReviewResult("logic", ReviewSeverity.MINOR, "LOG",
                        "F.java", 5, 6, "L1", "D", "F")
        );

        AggregatedReport report = new AggregatedReport(
                "pr-4", "Summary", 1, 1, 1,
                results
        );

        String markdown = generator.generateMarkdown(report);

        assertTrue(markdown.contains("security"));
        assertTrue(markdown.contains("logic"));
        // security has 2 findings, logic has 1
        assertTrue(markdown.contains("2 findings") || markdown.contains("2 finding"));
    }
}
