package com.ai.code.review.aggregation;

import com.ai.code.review.model.AggregatedReport;
import com.ai.code.review.model.ReviewResult;
import com.ai.code.review.model.ReviewSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResultAggregator covering deduplication, severity sorting,
 * empty lists, single results, and mixed severities.
 */
class ResultAggregatorTest {

    private ResultAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new ResultAggregator();
    }

    /**
     * Tests that an empty input list produces a report with no results and zero counts.
     */
    @Test
    void testEmptyListReturnsEmptyReport() {
        AggregatedReport report = aggregator.aggregate(List.of(), "pr-1");

        assertEquals("pr-1", report.prId());
        assertEquals(0, report.criticalCount());
        assertEquals(0, report.majorCount());
        assertEquals(0, report.minorCount());
        assertTrue(report.results().isEmpty());
        assertTrue(report.summary().contains("No issues found"));
    }

    /**
     * Tests that a null input list produces a report with no results.
     */
    @Test
    void testNullListReturnsEmptyReport() {
        AggregatedReport report = aggregator.aggregate(null, "pr-2");

        assertEquals("pr-2", report.prId());
        assertTrue(report.results().isEmpty());
    }

    /**
     * Tests that a single result is correctly aggregated.
     */
    @Test
    void testSingleResultIsPreserved() {
        ReviewResult result = new ReviewResult("security", ReviewSeverity.CRITICAL,
                "SQL_INJECTION", "src/main/java/UserService.java",
                10, 15, "SQL Injection Risk", "User input not sanitized", "Use prepared statements");

        AggregatedReport report = aggregator.aggregate(List.of(result), "pr-3");

        assertEquals(1, report.results().size());
        assertEquals(1, report.criticalCount());
        assertEquals(0, report.majorCount());
        assertEquals(0, report.minorCount());
        assertEquals(result, report.results().getFirst());
        assertTrue(report.summary().contains("1 critical"));
    }

    /**
     * Tests that results with the same deduplication key are collapsed,
     * keeping the entry with the highest severity.
     */
    @Test
    void testDeduplicationKeepsHighestSeverity() {
        ReviewResult minor = new ReviewResult("code-style", ReviewSeverity.MINOR,
                "NAMING", "FileService.java", 5, 10,
                "Poor naming", "Name could be better", "Rename it");
        ReviewResult major = new ReviewResult("logic", ReviewSeverity.MAJOR,
                "NAMING", "FileService.java", 5, 10,
                "Poor naming", "Name could be better", "Rename it");
        ReviewResult critical = new ReviewResult("security", ReviewSeverity.CRITICAL,
                "NAMING", "FileService.java", 5, 10,
                "Poor naming", "Name could be better", "Rename it");

        // Add in reverse order of severity
        AggregatedReport report = aggregator.aggregate(
                List.of(minor, major, critical), "pr-4");

        // Should keep only one entry (highest severity: CRITICAL)
        assertEquals(1, report.results().size());
        assertEquals(ReviewSeverity.CRITICAL, report.results().getFirst().severity());
        assertEquals("security", report.results().getFirst().agentId());
    }

    /**
     * Tests that results with different deduplication keys are all kept.
     */
    @Test
    void testDifferentKeysNotDeduplicated() {
        ReviewResult result1 = new ReviewResult("security", ReviewSeverity.CRITICAL,
                "SQL_INJECTION", "UserService.java", 10, 15,
                "SQL Injection", "Risk", "Fix");
        ReviewResult result2 = new ReviewResult("logic", ReviewSeverity.MAJOR,
                "NULL_CHECK", "UserService.java", 25, 30,
                "Null check", "May be null", "Add check");

        AggregatedReport report = aggregator.aggregate(List.of(result1, result2), "pr-5");

        assertEquals(2, report.results().size());
    }

    /**
     * Tests that results are sorted by severity first, then by file path, then by line.
     */
    @Test
    void testSortingBySeverityThenFileThenLine() {
        ReviewResult info1 = new ReviewResult("code-style", ReviewSeverity.INFO,
                "FORMAT", "AFile.java", 1, 5, "Format issue", "Desc", "Fix");
        ReviewResult critical1 = new ReviewResult("security", ReviewSeverity.CRITICAL,
                "SECURITY", "BFile.java", 10, 20, "Security issue", "Desc", "Fix");
        ReviewResult major1 = new ReviewResult("logic", ReviewSeverity.MAJOR,
                "LOGIC", "AFile.java", 30, 40, "Logic issue", "Desc", "Fix");
        ReviewResult info2 = new ReviewResult("code-style", ReviewSeverity.INFO,
                "FORMAT", "AFile.java", 50, 55, "Another format", "Desc", "Fix");

        AggregatedReport report = aggregator.aggregate(
                List.of(info1, critical1, major1, info2), "pr-6");

        List<ReviewResult> results = report.results();
        assertEquals(4, results.size());

        // Order should be: CRITICAL > MAJOR > INFO > INFO
        // But BFile comes after AFile alphabetically within same severity
        // Expected: critical1 (CRITICAL, BFile), major1 (MAJOR, AFile), info1 (INFO, AFile, line 1), info2 (INFO, AFile, line 50)
        assertEquals(ReviewSeverity.CRITICAL, results.get(0).severity());
        assertEquals(ReviewSeverity.MAJOR, results.get(1).severity());
        assertEquals(ReviewSeverity.INFO, results.get(2).severity());
        assertEquals(ReviewSeverity.INFO, results.get(3).severity());

        // Check within INFO, sorted by line
        assertEquals(1, results.get(2).lineStart());
        assertEquals(50, results.get(3).lineStart());
    }

    /**
     * Tests that severity counts are accurate with mixed severities.
     */
    @Test
    void testMixedSeverityCounts() {
        ReviewResult critical = new ReviewResult("security", ReviewSeverity.CRITICAL,
                "SQL", "F1.java", 1, 2, "C1", "D", "S");
        ReviewResult major1 = new ReviewResult("logic", ReviewSeverity.MAJOR,
                "LOGIC", "F2.java", 3, 4, "M1", "D", "S");
        ReviewResult major2 = new ReviewResult("architecture", ReviewSeverity.MAJOR,
                "ARCH", "F3.java", 5, 6, "M2", "D", "S");
        ReviewResult minor = new ReviewResult("code-style", ReviewSeverity.MINOR,
                "STYLE", "F4.java", 7, 8, "N1", "D", "S");
        ReviewResult info = new ReviewResult("code-style", ReviewSeverity.INFO,
                "FORMAT", "F5.java", 9, 10, "I1", "D", "S");

        AggregatedReport report = aggregator.aggregate(
                List.of(critical, major1, major2, minor, info), "pr-7");

        assertEquals(1, report.criticalCount());
        assertEquals(2, report.majorCount());
        assertEquals(1, report.minorCount());
        assertEquals(5, report.results().size());
    }

    /**
     * Tests that files with null file paths are handled gracefully.
     */
    @Test
    void testNullFilePathIsHandled() {
        ReviewResult result1 = new ReviewResult("security", ReviewSeverity.CRITICAL,
                "SEC", null, 1, 2, "Issue", "Desc", "Fix");
        ReviewResult result2 = new ReviewResult("logic", ReviewSeverity.MAJOR,
                "LOGIC", null, 3, 4, "Issue2", "Desc", "Fix");
        ReviewResult result3 = new ReviewResult("security", ReviewSeverity.CRITICAL,
                "SEC", null, 1, 2, "Dup", "Desc", "Fix"); // dup of result1

        AggregatedReport report = aggregator.aggregate(
                List.of(result1, result2, result3), "pr-8");

        // result1 and result3 have same composite key (null path + 1|2 + SEC),
        // result2 is different. So we should have 2 results.
        assertEquals(2, report.results().size());
    }

    /**
     * Tests the summary text output format.
     */
    @Test
    void testSummaryFormat() {
        ReviewResult r1 = new ReviewResult("security", ReviewSeverity.CRITICAL,
                "SEC", "File.java", 1, 2, "C1", "D", "S");
        ReviewResult r2 = new ReviewResult("logic", ReviewSeverity.MAJOR,
                "LOG", "File2.java", 3, 4, "M1", "D", "S");

        AggregatedReport report = aggregator.aggregate(List.of(r1, r2), "pr-9");

        String summary = report.summary();
        assertTrue(summary.contains("1 critical"));
        assertTrue(summary.contains("1 major"));
        assertTrue(summary.contains("0 minor"));
        assertTrue(summary.contains("2 files"));
    }
}
