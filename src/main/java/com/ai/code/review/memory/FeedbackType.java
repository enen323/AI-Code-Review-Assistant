package com.ai.code.review.memory;

/**
 * Represents the type of feedback a user provides on a review result.
 *
 * ACCEPTED: The review finding was accepted as valid.
 * DISMISSED: The review finding was dismissed as a false positive.
 */
public enum FeedbackType {
    ACCEPTED,
    DISMISSED
}
