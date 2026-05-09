package com.ai.code.review.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FeedbackCollector.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackCollectorTest {

    @Mock
    private MemoryService memoryService;

    private FeedbackCollector feedbackCollector;

    @Captor
    private ArgumentCaptor<FeedbackRecord> recordCaptor;

    @BeforeEach
    void setUp() {
        feedbackCollector = new FeedbackCollector(memoryService);
    }

    @Test
    void testExtractAcceptDirective() {
        var directive = feedbackCollector.extractFeedbackDirective("/feedback accept SQL_INJECTION");
        assertNotNull(directive);
        assertEquals(FeedbackType.ACCEPTED, directive.feedback());
        assertEquals("SQL_INJECTION", directive.ruleCategory());
    }

    @Test
    void testExtractDismissDirective() {
        var directive = feedbackCollector.extractFeedbackDirective("/feedback dismiss NULL_POINTER");
        assertNotNull(directive);
        assertEquals(FeedbackType.DISMISSED, directive.feedback());
        assertEquals("NULL_POINTER", directive.ruleCategory());
    }

    @Test
    void testExtractDirectiveWithFilePathAndLine() {
        var directive = feedbackCollector.extractFeedbackDirective(
                "/feedback dismiss SQL_INJECTION src/main/java/Test.java 42");
        assertNotNull(directive);
        assertEquals(FeedbackType.DISMISSED, directive.feedback());
        assertEquals("SQL_INJECTION", directive.ruleCategory());
        assertEquals("src/main/java/Test.java", directive.filePath());
        assertEquals(42, directive.lineStart());
    }

    @Test
    void testExtractDirectiveCaseInsensitive() {
        var directive = feedbackCollector.extractFeedbackDirective("/feedback ACCEPT SQL_INJECTION");
        assertNotNull(directive);
        assertEquals(FeedbackType.ACCEPTED, directive.feedback());
    }

    @Test
    void testExtractDirectiveWithLeadingTrailingSpaces() {
        var directive = feedbackCollector.extractFeedbackDirective(
                "  /feedback dismiss NULL_POINTER   ");
        assertNotNull(directive);
        assertEquals(FeedbackType.DISMISSED, directive.feedback());
        assertEquals("NULL_POINTER", directive.ruleCategory());
    }

    @Test
    void testExtractDirectiveInvalidFormatReturnsNull() {
        assertNull(feedbackCollector.extractFeedbackDirective("This is a normal comment"));
        assertNull(feedbackCollector.extractFeedbackDirective("/feedback unknown SQL_INJECTION"));
        assertNull(feedbackCollector.extractFeedbackDirective("/feedback accept"));
        assertNull(feedbackCollector.extractFeedbackDirective(""));
        assertNull(feedbackCollector.extractFeedbackDirective(null));
    }

    @Test
    void testDeriveAgentTypeFromRuleCategory() {
        // Security-related categories
        var directive = feedbackCollector.extractFeedbackDirective("/feedback dismiss SQL_INJECTION");
        assertNotNull(directive);
        assertEquals("security", directive.agentType());

        directive = feedbackCollector.extractFeedbackDirective("/feedback accept XSS_VULNERABILITY");
        assertNotNull(directive);
        assertEquals("security", directive.agentType());

        // Logic-related categories
        directive = feedbackCollector.extractFeedbackDirective("/feedback dismiss NULL_POINTER");
        assertNotNull(directive);
        assertEquals("logic", directive.agentType());

        directive = feedbackCollector.extractFeedbackDirective("/feedback accept LOGIC_ERROR");
        assertNotNull(directive);
        assertEquals("logic", directive.agentType());

        // Codestyle-related categories
        directive = feedbackCollector.extractFeedbackDirective("/feedback dismiss NAMING_CONVENTION");
        assertNotNull(directive);
        assertEquals("codestyle", directive.agentType());

        // Architecture-related categories
        directive = feedbackCollector.extractFeedbackDirective("/feedback dismiss LAYER_VIOLATION");
        assertNotNull(directive);
        assertEquals("architecture", directive.agentType());

        directive = feedbackCollector.extractFeedbackDirective("/feedback accept CIRCULAR_DEPENDENCY");
        assertNotNull(directive);
        assertEquals("architecture", directive.agentType());
    }

    @Test
    void testUnknownRuleCategoryDefaultsToUnknownAgent() {
        var directive = feedbackCollector.extractFeedbackDirective("/feedback dismiss CUSTOM_RULE_123");
        assertNotNull(directive);
        assertEquals("unknown", directive.agentType());
    }

    @Test
    void testRecordFeedbackStoresAccepted() {
        feedbackCollector.recordFeedback("owner/repo", 42,
                "/feedback accept SQL_INJECTION");

        verify(memoryService).storeFeedback(recordCaptor.capture());
        FeedbackRecord captured = recordCaptor.getValue();

        assertEquals("owner/repo#42", captured.prId());
        assertEquals("SQL_INJECTION", captured.ruleCategory());
        assertEquals("security", captured.agentType());
        assertEquals(FeedbackType.ACCEPTED, captured.feedback());
    }

    @Test
    void testRecordFeedbackStoresDismissed() {
        feedbackCollector.recordFeedback("owner/repo", 42,
                "/feedback dismiss NULL_POINTER src/main/java/App.java 15");

        verify(memoryService).storeFeedback(recordCaptor.capture());
        FeedbackRecord captured = recordCaptor.getValue();

        assertEquals("owner/repo#42", captured.prId());
        assertEquals("NULL_POINTER", captured.ruleCategory());
        assertEquals("logic", captured.agentType());
        assertEquals(FeedbackType.DISMISSED, captured.feedback());
        assertEquals("src/main/java/App.java", captured.filePath());
        assertEquals(15, captured.lineStart());
    }

    @Test
    void testRecordFeedbackThrowsOnInvalidComment() {
        assertThrows(IllegalArgumentException.class,
                () -> feedbackCollector.recordFeedback("owner/repo", 42, "invalid comment"));
    }
}
