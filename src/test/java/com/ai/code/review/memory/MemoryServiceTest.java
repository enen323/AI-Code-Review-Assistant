package com.ai.code.review.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private FeedbackRecordMapper feedbackRecordMapper;

    @Mock
    private RuleStatsMapper ruleStatsMapper;

    private MemoryService memoryService;

    @Captor
    private ArgumentCaptor<FeedbackRecordEntity> entityCaptor;

    @BeforeEach
    void setUp() {
        memoryService = new MemoryService(feedbackRecordMapper, ruleStatsMapper, 0.7);
    }

    @Test
    void testStoreFeedbackAccepted() {
        FeedbackRecord record = new FeedbackRecord(
                "owner/repo#42", "src/main/java/Test.java", 10,
                "SQL_INJECTION", "security", "SELECT * FROM users WHERE id = ",
                FeedbackType.ACCEPTED);

        memoryService.storeFeedback(record);

        // Verify INSERT into review_feedback
        verify(feedbackRecordMapper).insert(entityCaptor.capture());
        FeedbackRecordEntity entity = entityCaptor.getValue();
        assertEquals("owner/repo#42", entity.getPrId());
        assertEquals("SQL_INJECTION", entity.getRuleCategory());
        assertEquals("ACCEPTED", entity.getFeedback());

        // Verify UPSERT into rule_stats
        verify(ruleStatsMapper).upsertStats("security", "SQL_INJECTION", true);
    }

    @Test
    void testStoreFeedbackDismissedTriggersDowngradeCheck() {
        // Setup: rule already has 3 dismissals → after 4th, ratio = 1.0 > 0.7
        when(ruleStatsMapper.selectOne(any())).thenReturn(
                new RuleStatsEntity("security", "SQL_INJECTION") {{
                    setTotal(3);
                    setAccepted(0);
                    setDismissed(3);
                }}
        );

        FeedbackRecord record = new FeedbackRecord(
                "owner/repo#42", "src/main/java/Test.java", 10,
                "SQL_INJECTION", "security", "SELECT * FROM users WHERE id = ",
                FeedbackType.DISMISSED);

        memoryService.storeFeedback(record);

        verify(ruleStatsMapper).upsertStats("security", "SQL_INJECTION", false);
        // Downgrade should be triggered: 4/4 = 1.0 > 0.7
        verify(ruleStatsMapper).markDowngraded("security", "SQL_INJECTION");
    }

    @Test
    void testStoreFeedbackDismissedBelowThresholdNoDowngrade() {
        // Setup: rule has 2 dismissals out of 10 → ratio = 0.2 < 0.7
        when(ruleStatsMapper.selectOne(any())).thenReturn(
                new RuleStatsEntity("security", "SQL_INJECTION") {{
                    setTotal(10);
                    setAccepted(8);
                    setDismissed(2);
                }}
        );

        FeedbackRecord record = new FeedbackRecord(
                "owner/repo#42", "src/main/java/Test.java", 10,
                "SQL_INJECTION", "security", "SELECT * FROM users WHERE id = ",
                FeedbackType.DISMISSED);

        memoryService.storeFeedback(record);

        verify(ruleStatsMapper).upsertStats("security", "SQL_INJECTION", false);
        // Ratio 3/11 = 0.27 < 0.7, no downgrade
        verify(ruleStatsMapper, never()).markDowngraded(anyString(), anyString());
    }

    @Test
    void testGetRuleStats() {
        RuleStatsEntity e1 = new RuleStatsEntity("security", "SQL_INJECTION");
        e1.setTotal(5); e1.setAccepted(3); e1.setDismissed(2);

        RuleStatsEntity e2 = new RuleStatsEntity("logic", "NULL_POINTER");
        e2.setTotal(10); e2.setAccepted(2); e2.setDismissed(8); e2.setDowngraded(true);

        when(ruleStatsMapper.selectList(null)).thenReturn(List.of(e1, e2));

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
        RuleStatsEntity entity = new RuleStatsEntity("security", "XSS");
        entity.setTotal(3); entity.setAccepted(1); entity.setDismissed(2);

        when(ruleStatsMapper.selectOne(any())).thenReturn(entity);

        RuleStats stats = memoryService.getRuleStats("security", "XSS");

        assertNotNull(stats);
        assertEquals("XSS", stats.ruleCategory());
        assertEquals(3, stats.total());
        assertEquals(1, stats.accepted());
        assertEquals(2, stats.dismissed());
    }

    @Test
    void testGetRuleStatsForNonExistentRuleReturnsNull() {
        when(ruleStatsMapper.selectOne(any())).thenReturn(null);

        RuleStats stats = memoryService.getRuleStats("security", "NONEXISTENT");
        assertNull(stats);
    }

    @Test
    void testIsRuleDowngraded() {
        when(ruleStatsMapper.selectCount(any())).thenReturn(1L);

        assertTrue(memoryService.isRuleDowngraded("logic", "NULL_POINTER"));
    }

    @Test
    void testIsRuleDowngradedReturnsFalseWhenNoStats() {
        when(ruleStatsMapper.selectCount(any())).thenReturn(0L);

        assertFalse(memoryService.isRuleDowngraded("security", "FAKE"));
    }

    @Test
    void testResetRuleStats() {
        memoryService.resetRuleStats("security", "SQL_INJECTION");

        verify(ruleStatsMapper).delete(any());
    }

    @Test
    void testGetRecentFeedback() {
        FeedbackRecordEntity entity = new FeedbackRecordEntity();
        entity.setId(1L);
        entity.setPrId("repo#1");
        entity.setFilePath("Test.java");
        entity.setLineStart(10);
        entity.setRuleCategory("SQL_INJECTION");
        entity.setAgentType("security");
        entity.setCodeSnippet("SELECT * FROM ");
        entity.setFeedback("DISMISSED");
        entity.setCreatedAt(LocalDateTime.now());

        when(feedbackRecordMapper.selectList(any())).thenReturn(List.of(entity));

        List<FeedbackRecord> feedback = memoryService.getRecentFeedback("Test.java", 10);

        assertEquals(1, feedback.size());
        assertEquals("SQL_INJECTION", feedback.get(0).ruleCategory());
        assertEquals(FeedbackType.DISMISSED, feedback.get(0).feedback());
    }

    @Test
    void testGetMemoryHintsWithDowngradedRules() {
        RuleStatsEntity downgraded = new RuleStatsEntity("logic", "NULL_POINTER");
        downgraded.setTotal(10); downgraded.setAccepted(2); downgraded.setDismissed(8);
        downgraded.setDowngraded(true);

        when(ruleStatsMapper.selectList(null)).thenReturn(List.of(downgraded));
        when(feedbackRecordMapper.selectCount(any())).thenReturn(0L);

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
