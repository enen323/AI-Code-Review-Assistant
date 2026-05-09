package com.ai.code.review.model;

public record ReviewTask(
    String prId,
    String repoName,
    int prNumber,
    String prTitle,
    String prDescription,
    String sourceBranch,
    String targetBranch,
    String commitSha,
    String diffUrl,
    TriggerEvent triggerEvent
) {}
