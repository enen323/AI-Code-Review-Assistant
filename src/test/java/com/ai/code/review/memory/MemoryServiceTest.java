package com.ai.code.review.memory;

import com.ai.code.review.model.ChangeType;
import com.ai.code.review.model.ChangedFile;
import com.ai.code.review.model.CodeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MemoryService.
 */
@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private MemoryService memoryService;

    @Captor
    private ArgumentCaptor<Object[]> argsCaptor;

    @BeforeEach
    void setUp() {
        memoryService = new MemoryService(jdbcTemplate, 0.7);
    }

    @Test
    void testStoreFeedbackAccepted() {
        FeedbackRecord record = new FeedbackRecord(
                "owner/repo#42", "src/main/java/Test.java", 10,
                "SQL_INJECTION", "security", "SELECT * FROM users WHERE id = ",
                FeedbackType.ACCEPTED);

        memoryService.storeFeedback(record);

        // Verify INSERT into review_feedback
        verify(jdbcTemplate).update(
                eq("INSERT INTO review_feedback (pr_id, file_path, line_start, rule_category, agent_type, code_snippet, feedback, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"),
                eq("owner/repo#42"), eq("src/main/java/Test.java"), eq(10),
                eq("SQL_INJECTION"), eq("security"), eq("SELECT * FROM users WHERE id = "),
                eq("ACCEPTED"), any(LocalDateTime.class));

        // Verify UPSERT into rule_stats
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("INSERT INTO rule_stats")),
                eq("security"), eq("SQL_INJECTION"), eq(1), eq(0), eq("ACCEPTED"), eq("ACCEPTED"));
    }

    @Test
    void testStoreFeedbackDismissedTriggersDowngradeCheck() {
        // Setup: rule already has 2 dismissals and 0 acceptances
        when(jdbcTemplate.query(
                contains("FROM rule_stats WHERE agent_type = ? AND rule_category = ?"),
                any(RowMapper.class),
                eq("security"), eq("SQL_INJECTION")))
                .thenReturn(List.of(RuleStats.fromCounts("SQL_INJECTION", "security", 3, 0, 3, false)));

        // Stub initial INSERT into review_feedback
        when(jdbcTemplate.update(
                contains("INSERT INTO review_feedback"),
                anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(),
                anyString(), any(LocalDateTime.class)))
                .thenReturn(1);

        // Stub UPSERT into rule_stats
        when(jdbcTemplate.update(
                contains("INSERT INTO rule_stats"),
                eq("security"), eq("SQL_INJECTION"), eq(0), eq(1), eq("DISMISSED"), eq("DISMISSED")))
                .thenReturn(1);

        FeedbackRecord record = new FeedbackRecord(
                "owner/repo#42", "src/main/java/Test.java", 10,
                "SQL_INJECTION", "security", "SELECT * FROM users WHERE id = ",
                FeedbackType.DISMISSED);

        memoryService.storeFeedback(record);

        // After storing DISMISSED, the dismiss ratio should be 4/4 = 1.0 > 0.7
        // So it should trigger the downgrade update
        verify(jdbcTemplate).update(
                eq("UPDATE rule_stats SET is_downgraded = true WHERE agent_type = ? AND rule_category = ?"),
                eq("security"), eq("SQL_INJECTION"));
    }

    @Test
    void testGetRuleStats() {
        when(jdbcTemplate.query(
                contains("FROM rule_stats ORDER BY agent_type, rule_category"),
                any(RowMapper.class)))
                .thenReturn(List.of(
                        RuleStats.fromCounts("SQL_INJECTION", "security", 5, 3, 2, false),
                        RuleStats.fromCounts("NULL_POINTER", "logic", 10, 2, 8, true)
                ));

        List<RuleStats> stats = memoryService.getRuleStats();

        assertEquals(2, stats.size());
        assertEquals("SQL_INJECTION", stats.get(0).ruleCategory());
        assertEquals("security", stats.get(0).agentType());
        assertEquals(5, stats.get(0).total());
        assertEquals(0.4, stats.get(0).dismissRatio(), 0.001);
        assertFalse(stats.get(0).isDowngraded());

        assertEquals("NULL_POINTER", stats.get(1).ruleCategory());
        assertEquals("logic", stats.get(1).agentType());
        assertEquals(10, stats.get(1).total());
        assertEquals(0.8, stats.get(1).dismissRatio(), 0.001);
        assertTrue(stats.get(1).isDowngraded());
    }

    @Test
    void testGetRuleStatsForSpecificRule() {
        when(jdbcTemplate.query(
                contains("FROM rule_stats WHERE agent_type = ? AND rule_category = ?"),
                any(RowMapper.class),
                eq("security"), eq("XSS")))
                .thenReturn(List.of(
                        RuleStats.fromCounts("XSS", "security", 3, 1, 2, false)
                ));

        RuleStats stats = memoryService.getRuleStats("security", "XSS");

        assertNotNull(stats);
        assertEquals("XSS", stats.ruleCategory());
        assertEquals(3, stats.total());
        assertEquals(1, stats.accepted());
        assertEquals(2, stats.dismissed());
    }

    @Test
    void testGetRuleStatsForNonExistentRuleReturnsNull() {
        when(jdbcTemplate.query(
                contains("FROM rule_stats WHERE agent_type = ? AND rule_category = ?"),
                any(RowMapper.class),
                eq("security"), eq("NONEXISTENT")))
                .thenReturn(List.of());

        RuleStats stats = memoryService.getRuleStats("security", "NONEXISTENT");
        assertNull(stats);
    }

    @Test
    void testIsRuleDowngraded() {
        when(jdbcTemplate.query(
                contains("SELECT is_downgraded FROM rule_stats WHERE agent_type = ? AND rule_category = ?"),
                any(RowMapper.class),
                eq("logic"), eq("NULL_POINTER")))
                .thenReturn(List.of(true));

        assertTrue(memoryService.isRuleDowngraded("logic", "NULL_POINTER"));
    }

    @Test
    void testIsRuleDowngradedReturnsFalseWhenNoStats() {
        when(jdbcTemplate.query(
                contains("SELECT is_downgraded FROM rule_stats"),
                any(RowMapper.class),
                eq("security"), eq("FAKE")))
                .thenReturn(List.of());

        assertFalse(memoryService.isRuleDowngraded("security", "FAKE"));
    }

    @Test
    void testResetRuleStats() {
        memoryService.resetRuleStats("security", "SQL_INJECTION");

        verify(jdbcTemplate).update(
                eq("DELETE FROM rule_stats WHERE agent_type = ? AND rule_category = ?"),
                eq("security"), eq("SQL_INJECTION"));
    }

    @Test
    void testGetRecentFeedback() {
        when(jdbcTemplate.query(
                contains("FROM review_feedback WHERE file_path = ? AND line_start = ?"),
                any(RowMapper.class),
                eq("Test.java"), eq(10)))
                .thenReturn(List.of(
                        new FeedbackRecord(1L, "repo#1", "Test.java", 10,
                                "SQL_INJECTION", "security", "SELECT * FROM ",
                                FeedbackType.DISMISSED, LocalDateTime.now())
                ));

        List<FeedbackRecord> feedback = memoryService.getRecentFeedback("Test.java", 10);

        assertEquals(1, feedback.size());
        assertEquals("SQL_INJECTION", feedback.get(0).ruleCategory());
        assertEquals(FeedbackType.DISMISSED, feedback.get(0).feedback());
    }

    @Test
    void testGetMemoryHintsWithDowngradedRules() {
        when(jdbcTemplate.query(
                contains("FROM rule_stats ORDER BY agent_type, rule_category"),
                any(RowMapper.class)))
                .thenReturn(List.of(
                        RuleStats.fromCounts("NULL_POINTER", "logic", 10, 2, 8, true)
                ));

        // Mock no file feedback
        when(jdbcTemplate.queryForObject(
                contains("FROM review_feedback WHERE file_path = ?"),
                eq(Integer.class),
                anyString()))
                .thenReturn(0);

        CodeContext context = new CodeContext(
                "pr1",
                List.of(new ChangedFile("Test.java", ChangeType.MODIFIED, 10, 5,
                        "diff content")),
                null, null, null);

        List<String> hints = memoryService.getMemoryHints(context);

        assertFalse(hints.isEmpty());
        assertTrue(hints.stream().anyMatch(h -> h.contains("NULL_POINTER")));
        assertTrue(hints.stream().anyMatch(h -> h.contains("downgraded")));
    }

    @Test
    void testGetMemoryHintsWithNullContextReturnsEmpty() {
        List<String> hints = memoryService.getMemoryHints(null);
        assertTrue(hints.isEmpty());
    }

    @Test
    void testRuleStatsFromCounts() {
        RuleStats stats = RuleStats.fromCounts("TEST_RULE", "test-agent", 10, 3, 7, false);
        assertEquals(0.7, stats.dismissRatio(), 0.001);
        assertFalse(stats.isDowngraded());

        stats = RuleStats.fromCounts("TEST_RULE", "test-agent", 0, 0, 0, false);
        assertEquals(0.0, stats.dismissRatio(), 0.001);
    }
}
