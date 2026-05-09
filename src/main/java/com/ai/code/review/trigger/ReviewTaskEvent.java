package com.ai.code.review.trigger;

import com.ai.code.review.model.ReviewTask;

/**
 * Spring application event wrapping a ReviewTask.
 * Published when a valid webhook request is received.
 */
public class ReviewTaskEvent {

    private final ReviewTask reviewTask;

    public ReviewTaskEvent(ReviewTask reviewTask) {
        this.reviewTask = reviewTask;
    }

    public ReviewTask getReviewTask() {
        return reviewTask;
    }
}
