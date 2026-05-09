package com.ai.code.review.model;

import java.util.List;

public record AggregatedReport(
    String prId,
    String summary,
    int criticalCount,
    int majorCount,
    int minorCount,
    List<ReviewResult> results
) {}
