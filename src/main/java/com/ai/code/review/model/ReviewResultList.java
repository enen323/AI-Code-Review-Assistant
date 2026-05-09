package com.ai.code.review.model;

import java.util.List;

/**
 * Wrapper record for LLM multi-result output.
 * Enables BeanOutputConverter to deserialize array of ReviewResult.
 */
public record ReviewResultList(List<ReviewResult> results) {
}
