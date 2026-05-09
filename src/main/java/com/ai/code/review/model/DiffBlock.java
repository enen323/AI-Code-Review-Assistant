package com.ai.code.review.model;

import java.util.List;

public record DiffBlock(
    String filePath,
    int oldStartLine,
    int newStartLine,
    int oldLines,
    int newLines,
    String header,
    List<String> lines
) {}
