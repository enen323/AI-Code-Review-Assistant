package com.ai.code.review.model;

import java.util.List;

public record CodeContext(
    String prId,
    List<ChangedFile> changedFiles,
    List<DiffBlock> diffBlocks,
    String dependencyGraph,
    String relatedHistory
) {}
