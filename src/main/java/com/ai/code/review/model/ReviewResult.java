package com.ai.code.review.model;

public record ReviewResult(
    String agentId,
    ReviewSeverity severity,
    String category,
    String filePath,
    int lineStart,
    int lineEnd,
    String title,
    String description,
    String suggestion
) {}
