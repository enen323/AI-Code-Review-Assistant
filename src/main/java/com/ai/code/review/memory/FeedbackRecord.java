package com.ai.code.review.memory;

import java.time.LocalDateTime;

/**
 * Record for storing user feedback on a review result.
 *
 * Captures which rule (ruleCategory + agentType) was accepted or dismissed
 * by the user, along with the code location and context.
 */
public record FeedbackRecord(
    Long id,
    String prId,
    String filePath,
    int lineStart,
    String ruleCategory,
    String agentType,
    String codeSnippet,
    FeedbackType feedback,
    LocalDateTime createdAt
) {

    /**
     * Convenience constructor for new feedback entries without an id or timestamp.
     */
    public FeedbackRecord(String prId, String filePath, int lineStart,
                          String ruleCategory, String agentType,
                          String codeSnippet, FeedbackType feedback) {
        this(null, prId, filePath, lineStart, ruleCategory, agentType,
             codeSnippet, feedback, LocalDateTime.now());
    }
}
