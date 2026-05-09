package com.ai.code.review.context;

import com.ai.code.review.model.DiffBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Parses unified diff text into structured DiffBlock objects.
 *
 * Handles standard unified diff format produced by git diff.
 */
@Component
public class GitDiffParser {

    // Pattern for hunk headers: @@ -oldStart,oldLines +newStart,newLines @@
    private static final Pattern HUNK_HEADER_PATTERN =
            Pattern.compile("^@@\\s+-?(\\d+)(?:,(\\d+))?\\s+\\+?(\\d+)(?:,(\\d+))?\\s+@@.*$");

    /**
     * Parses a unified diff string for a single file into a list of DiffBlocks.
     *
     * @param fileName    the name of the file being diffed
     * @param unifiedDiff the unified diff text for the file
     * @return list of parsed DiffBlock objects
     */
    public List<DiffBlock> parseDiff(String fileName, String unifiedDiff) {
        List<DiffBlock> blocks = new ArrayList<>();

        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            return blocks;
        }

        String[] lines = unifiedDiff.split("\n", -1);
        List<String> currentLines = new ArrayList<>();
        int oldStart = 0;
        int newStart = 0;
        int oldLines = 0;
        int newLines = 0;
        boolean inHunk = false;

        for (String line : lines) {
            Matcher matcher = HUNK_HEADER_PATTERN.matcher(line);
            if (matcher.matches()) {
                // Save previous hunk if exists
                if (inHunk && !currentLines.isEmpty()) {
                    blocks.add(new DiffBlock(
                            fileName, oldStart, newStart,
                            oldLines, newLines,
                            buildHeader(oldStart, oldLines, newStart, newLines),
                            new ArrayList<>(currentLines)
                    ));
                    currentLines.clear();
                }

                // Parse hunk header
                oldStart = parseIntOrDefault(matcher.group(1), 1);
                oldLines = parseIntOrDefault(matcher.group(2), 1);
                newStart = parseIntOrDefault(matcher.group(3), 1);
                newLines = parseIntOrDefault(matcher.group(4), 1);
                inHunk = true;
            } else if (inHunk) {
                // Collect diff content lines (+, -, or space prefix)
                if (!line.isEmpty() && (line.charAt(0) == '+' || line.charAt(0) == '-'
                        || line.charAt(0) == ' ' || line.startsWith("\\ "))) {
                    currentLines.add(line);
                }
            }
        }

        // Save last hunk
        if (inHunk && !currentLines.isEmpty()) {
            blocks.add(new DiffBlock(
                    fileName, oldStart, newStart,
                    oldLines, newLines,
                    buildHeader(oldStart, oldLines, newStart, newLines),
                    new ArrayList<>(currentLines)
            ));
        }

        return blocks;
    }

    /**
     * Builds a unified diff hunk header string.
     */
    private String buildHeader(int oldStart, int oldLines, int newStart, int newLines) {
        return "@@ -" + oldStart + "," + oldLines + " +" + newStart + "," + newLines + " @@";
    }

    /**
     * Safely parses an integer from a string, returning a default value if parsing fails.
     */
    private int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
