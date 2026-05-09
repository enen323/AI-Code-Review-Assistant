package com.ai.code.review.agent;

import com.ai.code.review.model.CodeContext;
import com.ai.code.review.model.ReviewResult;

import java.util.List;

/**
 * Base interface for all review agents.
 *
 * Each agent type (security, logic, architecture, codestyle)
 * implements this interface to provide specialized code review
 * capabilities.
 */
@FunctionalInterface
public interface ReviewAgent {

    /**
     * Reviews the given code context and returns review results.
     *
     * @param context the code context to review
     * @return list of review results (may be empty)
     */
    List<ReviewResult> review(CodeContext context);

    /**
     * Returns the type identifier for this agent.
     *
     * @return agent type name (defaults to the class simple name)
     */
    default String getAgentType() {
        return this.getClass().getSimpleName();
    }
}
