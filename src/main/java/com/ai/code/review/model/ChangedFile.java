package com.ai.code.review.model;

public record ChangedFile(
    String filePath,
    ChangeType changeType,
    int additions,
    int deletions,
    String diffContent
) {}
