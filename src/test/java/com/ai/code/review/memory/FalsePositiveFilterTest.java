package com.ai.code.review.memory;

import com.ai.code.review.model.ReviewResult;
import com.ai.code.review.model.ReviewSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests for FalsePositiveFilter.
 */
@ExtendWith(MockitoExtension.class)
class FalsePositiveFilterTest {

    @Mock
    private MemoryService memoryService;

    private FalsePositiveFilter filter;

    @BeforeEach
    void setUp() {
        filter = new FalsePositiveFilter(memoryService);
    }

    @Test
    void testNonDowngradedRulePassesThrough() {
        // Non-downgraded rule: passes through unchanged
        when(memoryService.isRuleDowngraded("security", "SQL_INJECTION")).thenReturn(false);

        ReviewResult result = new ReviewResult(
                "security", ReviewSeverity.CRITICAL, "SQL_INJECTION",
                "Test.java", 10, 20,
                "SQL Injection found", "Description", "Suggestion");

        List<ReviewResult> filtered = filter.filter(List.of(result));

        assertEquals(1, filtered.size());
        assertEquals(ReviewSeverity.CRITICAL, filtered.get(0).severity());
        assertEquals("SQL Injection found", filtered.get(0).title());
    }

    @Test
    void testDowngradedRuleReducesSeverity() {
        // Downgraded rule: severity decreases by one level
        when(memoryService.isRuleDowngraded("security", "SQL_INJECTION")).thenReturn(true);
        when(memoryService.getRuleStats("security", "SQL_INJECTION"))
                .thenReturn(RuleStats.fromCounts("SQL_INJECTION", "security", 10, 6, 4, true));

        ReviewResult result = new ReviewResult(
                "security", ReviewSeverity.CRITICAL, "SQL_INJECTION",
                "Test.java", 10, 20,
                "SQL Injection found", "Description", "Suggestion");

        List<ReviewResult> filtered = filter.filter(List.of(result));

        assertEquals(1, filtered.size());
        assertEquals(ReviewSeverity.MAJOR, filtered.get(0).severity());
        assertTrue(filtered.get(0).description().contains("auto-downgraded"));
    }

    @Test
    void testCRITICALDowngradesToMAJOR() {
        when(memoryService.isRuleDowngraded("agent", "RULE")).thenReturn(true);
        when(memoryService.getRuleStats("agent", "RULE"))
                .thenReturn(RuleStats.fromCounts("RULE", "agent", 5, 3, 2, true));

        ReviewResult result = new ReviewResult(
                "agent", ReviewSeverity.CRITICAL, "RULE",
                "f.java", 1, 2, "t", "d", "s");

        assertEquals(ReviewSeverity.MAJOR, filter.filter(List.of(result)).get(0).severity());
    }

    @Test
    void testMAJORDowngradesToMINOR() {
        when(memoryService.isRuleDowngraded("agent", "RULE")).thenReturn(true);
        when(memoryService.getRuleStats("agent", "RULE"))
                .thenReturn(RuleStats.fromCounts("RULE", "agent", 5, 3, 2, true));

        ReviewResult result = new ReviewResult(
                "agent", ReviewSeverity.MAJOR, "RULE",
                "f.java", 1, 2, "t", "d", "s");

        assertEquals(ReviewSeverity.MINOR, filter.filter(List.of(result)).get(0).severity());
    }

    @Test
    void testMINORDowngradesToINFO() {
        when(memoryService.isRuleDowngraded("agent", "RULE")).thenReturn(true);
        when(memoryService.getRuleStats("agent", "RULE"))
                .thenReturn(RuleStats.fromCounts("RULE", "agent", 5, 3, 2, true));

        ReviewResult result = new ReviewResult(
                "agent", ReviewSeverity.MINOR, "RULE",
                "f.java", 1, 2, "t", "d", "s");

        assertEquals(ReviewSeverity.INFO, filter.filter(List.of(result)).get(0).severity());
    }

    @Test
    void testINFOStaysINFOWhenDowngraded() {
        when(memoryService.isRuleDowngraded("agent", "RULE")).thenReturn(true);
        when(memoryService.getRuleStats("agent", "RULE"))
                .thenReturn(RuleStats.fromCounts("RULE", "agent", 5, 3, 2, true));

        ReviewResult result = new ReviewResult(
                "agent", ReviewSeverity.INFO, "RULE",
                "f.java", 1, 2, "t", "d", "s");

        assertEquals(ReviewSeverity.INFO, filter.filter(List.of(result)).get(0).severity());
    }

    @Test
    void testRepeatedDismissalsSuppressResult() {
        // Dismissed > accepted AND downgraded => suppressed
        when(memoryService.isRuleDowngraded("security", "SQL_INJECTION")).thenReturn(true);
        when(memoryService.getRuleStats("security", "SQL_INJECTION"))
                .thenReturn(RuleStats.fromCounts("SQL_INJECTION", "security", 10, 2, 8, true));

        ReviewResult result = new ReviewResult(
                "security", ReviewSeverity.CRITICAL, "SQL_INJECTION",
                "Test.java", 10, 20,
                "SQL Injection found", "Description", "Suggestion");

        List<ReviewResult> filtered = filter.filter(List.of(result));

        assertTrue(filtered.isEmpty());
    }

    @Test
    void testSuppressionRequiresBothConditions() {
        // Dismissed > accepted but NOT downgraded => should NOT suppress
        when(memoryService.isRuleDowngraded("security", "SQL_INJECTION")).thenReturn(false);

        ReviewResult result = new ReviewResult(
                "security", ReviewSeverity.MAJOR, "SQL_INJECTION",
                "Test.java", 10, 20,
                "SQL Injection found", "Description", "Suggestion");

        List<ReviewResult> filtered = filter.filter(List.of(result));

        assertEquals(1, filtered.size());
        assertEquals(ReviewSeverity.MAJOR, filtered.get(0).severity());
    }

    @Test
    void testMixedResultsAreFilteredIndependently() {
        // First rule: not downgraded
        when(memoryService.isRuleDowngraded("security", "SQL_INJECTION")).thenReturn(false);
        // Second rule: downgraded with dismissed > accepted (suppressed)
        when(memoryService.isRuleDowngraded("logic", "NULL_POINTER")).thenReturn(true);
        when(memoryService.getRuleStats("logic", "NULL_POINTER"))
                .thenReturn(RuleStats.fromCounts("NULL_POINTER", "logic", 10, 1, 9, true));

        ReviewResult r1 = new ReviewResult(
                "security", ReviewSeverity.CRITICAL, "SQL_INJECTION",
                "Test.java", 10, 20, "SQL Injection", "desc", "sug");
        ReviewResult r2 = new ReviewResult(
                "logic", ReviewSeverity.MAJOR, "NULL_POINTER",
                "App.java", 5, 8, "Null Pointer", "desc", "sug");

        List<ReviewResult> filtered = filter.filter(List.of(r1, r2));

        assertEquals(1, filtered.size());
        assertEquals("SQL_INJECTION", filtered.get(0).category());
        assertEquals(ReviewSeverity.CRITICAL, filtered.get(0).severity());
    }

    @Test
    void testNullResultsReturnsEmptyList() {
        List<ReviewResult> filtered = filter.filter(null);
        assertTrue(filtered.isEmpty());
    }

    @Test
    void testEmptyResultsReturnsEmptyList() {
        List<ReviewResult> filtered = filter.filter(List.of());
        assertTrue(filtered.isEmpty());
    }

    @Test
    void testNullAgentTypePassesThrough() {
        ReviewResult result = new ReviewResult(
                null, ReviewSeverity.CRITICAL, "SQL_INJECTION",
                "Test.java", 10, 20, "Title", "Desc", "Sug");

        List<ReviewResult> filtered = filter.filter(List.of(result));
        assertEquals(1, filtered.size());
    }

    @Test
    void testDowngradedRuleWithAcceptedGreaterThanDismissedIsNotSuppressed() {
        // Dismissed NOT > accepted, even though downgraded => severity reduced but not suppressed
        when(memoryService.isRuleDowngraded("agent", "RULE")).thenReturn(true);
        when(memoryService.getRuleStats("agent", "RULE"))
                .thenReturn(RuleStats.fromCounts("RULE", "agent", 10, 6, 4, true));

        ReviewResult result = new ReviewResult(
                "agent", ReviewSeverity.CRITICAL, "RULE",
                "f.java", 1, 2, "t", "d", "s");

        List<ReviewResult> filtered = filter.filter(List.of(result));

        assertEquals(1, filtered.size());
        assertEquals(ReviewSeverity.MAJOR, filtered.get(0).severity());
    }

    @Test
    void testSuppressionWithDismissedEqualsAccepted() {
        // Dismissed == accepted, NOT > accepted, so should not suppress
        when(memoryService.isRuleDowngraded("agent", "RULE")).thenReturn(true);
        when(memoryService.getRuleStats("agent", "RULE"))
                .thenReturn(RuleStats.fromCounts("RULE", "agent", 10, 5, 5, true));

        ReviewResult result = new ReviewResult(
                "agent", ReviewSeverity.MAJOR, "RULE",
                "f.java", 1, 2, "t", "d", "s");

        List<ReviewResult> filtered = filter.filter(List.of(result));

        assertEquals(1, filtered.size());
        assertEquals(ReviewSeverity.MINOR, filtered.get(0).severity());
    }
}
