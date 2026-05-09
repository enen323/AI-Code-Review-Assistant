package com.ai.code.review.context;

import com.ai.code.review.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds CodeContext for agents from a ReviewTask.
 *
 * For the MVP, this parses diff content into ChangedFile and DiffBlock lists
 * and generates a simple dependency graph string from file extensions and imports.
 */
@Component
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final GitDiffParser diffParser;
    private final GitHubClient gitHubClient;

    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^[+-]?import\\s+(?:static\\s+)?([a-zA-Z0-9_.]+)\\s*;\\s*$", Pattern.MULTILINE);

    public ContextBuilder(GitDiffParser diffParser, GitHubClient gitHubClient) {
        this.diffParser = diffParser;
        this.gitHubClient = gitHubClient;
    }

    /**
     * Builds a CodeContext for the given review task.
     *
     * @param task the review task
     * @return the built CodeContext
     */
    public CodeContext build(ReviewTask task) {
        String diffContent;
        if (task.diffUrl() == null || task.diffUrl().isBlank()) {
            log.warn("Empty diff URL for task {}, cannot fetch diff. Check GITHUB_API_TOKEN.", task.prId());
            diffContent = "";
        } else {
            try {
                diffContent = gitHubClient.fetchDiff(task.diffUrl());
            } catch (Exception e) {
                log.warn("Failed to fetch diff for task {} from URL {}: {}",
                        task.prId(), task.diffUrl(), e.getMessage());
                diffContent = "";
            }
        }

        if (diffContent.isEmpty()) {
            log.warn("Empty diff content for task {}. Review will have no file context.", task.prId());
        }

        // Parse diff into files and blocks
        DiffParserResult parsed = parseDiffContent(task.prId(), diffContent);

        // Build dependency graph from imports
        String dependencyGraph = buildDependencyGraph(parsed.changedFiles);

        return new CodeContext(
                task.prId(),
                parsed.changedFiles,
                parsed.diffBlocks,
                dependencyGraph,
                "" // relatedHistory is empty for MVP
        );
    }

    /**
     * Builds a CodeContext from already-fetched diff content (for testing).
     *
     * @param task        the review task
     * @param diffContent the raw diff content
     * @return the built CodeContext
     */
    public CodeContext buildWithDiff(ReviewTask task, String diffContent) {
        DiffParserResult parsed = parseDiffContent(task.prId(), diffContent);
        String dependencyGraph = buildDependencyGraph(parsed.changedFiles);

        return new CodeContext(
                task.prId(),
                parsed.changedFiles,
                parsed.diffBlocks,
                dependencyGraph,
                ""
        );
    }

    /**
     * Parses raw diff content into ChangedFile and DiffBlock lists.
     */
    private DiffParserResult parseDiffContent(String prId, String diffContent) {
        List<ChangedFile> changedFiles = new ArrayList<>();
        List<DiffBlock> allDiffBlocks = new ArrayList<>();

        if (diffContent == null || diffContent.isBlank()) {
            return new DiffParserResult(changedFiles, allDiffBlocks);
        }

        // Split on diff --git lines (MULTILINE so ^ matches each line start)
        // String.split() lacks MULTILINE flag, use Pattern.compile()
        Pattern diffSplitPattern = Pattern.compile("(?m)(?=^diff --git )");
        String[] fileDiffs = diffSplitPattern.split(diffContent);
        if (fileDiffs.length == 0) {
            fileDiffs = new String[]{diffContent};
        }

        for (String fileDiff : fileDiffs) {
            if (fileDiff.isBlank()) {
                continue;
            }

            // Extract file path from ---/+++ lines
            String filePath = extractFilePath(fileDiff);
            if (filePath == null) {
                continue;
            }

            // Determine change type
            ChangeType changeType = determineChangeType(fileDiff);

            // Count additions and deletions
            int additions = countLinesByPrefix(fileDiff, '+');
            int deletions = countLinesByPrefix(fileDiff, '-');

            // Parse diff blocks
            List<DiffBlock> diffBlocks = diffParser.parseDiff(filePath, fileDiff);

            changedFiles.add(new ChangedFile(
                    filePath, changeType, additions, deletions, fileDiff
            ));
            allDiffBlocks.addAll(diffBlocks);
        }

        return new DiffParserResult(changedFiles, allDiffBlocks);
    }

    /**
     * Extracts the file path from a diff section.
     */
    private String extractFilePath(String fileDiff) {
        // Try +++ line first (new file path)
        Pattern plusPattern = Pattern.compile("^\\+\\+\\+\\s+b/(.*)$", Pattern.MULTILINE);
        Matcher matcher = plusPattern.matcher(fileDiff);
        if (matcher.find()) {
            String path = matcher.group(1).trim();
            if (!path.equals("/dev/null")) {
                return path;
            }
        }

        // Fall back to --- line
        Pattern minusPattern = Pattern.compile("^---\\s+a/(.*)$", Pattern.MULTILINE);
        matcher = minusPattern.matcher(fileDiff);
        if (matcher.find()) {
            String path = matcher.group(1).trim();
            if (!path.equals("/dev/null")) {
                return path;
            }
        }

        // Fall back to diff --git line
        Pattern diffPattern = Pattern.compile("^diff --git a/(.*)\\s+b/.*$", Pattern.MULTILINE);
        matcher = diffPattern.matcher(fileDiff);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return null;
    }

    /**
     * Determines the ChangeType from a diff section.
     */
    private ChangeType determineChangeType(String fileDiff) {
        if (fileDiff.contains("new file mode")) {
            return ChangeType.ADDED;
        }
        if (fileDiff.contains("deleted file mode")) {
            return ChangeType.DELETED;
        }
        if (fileDiff.contains("rename from")) {
            return ChangeType.RENAMED;
        }
        return ChangeType.MODIFIED;
    }

    /**
     * Counts lines starting with a given prefix character in a diff.
     * Skips the ---/+++ metadata lines that are exactly three repeated prefix chars.
     */
    private int countLinesByPrefix(String fileDiff, char prefix) {
        int count = 0;
        String triplePrefix = String.valueOf(prefix).repeat(3);
        String[] lines = fileDiff.split("\n");
        for (String line : lines) {
            if (line.startsWith(String.valueOf(prefix)) && !line.startsWith(triplePrefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Builds a simple dependency graph string from file imports and extensions.
     */
    private String buildDependencyGraph(List<ChangedFile> changedFiles) {
        if (changedFiles.isEmpty()) {
            return "";
        }

        StringBuilder graph = new StringBuilder();
        Set<String> extensions = new LinkedHashSet<>();
        Map<String, Set<String>> fileImports = new LinkedHashMap<>();

        for (ChangedFile file : changedFiles) {
            // Collect file extensions
            String ext = getFileExtension(file.filePath());
            if (ext != null) {
                extensions.add(ext);
            }

            // Extract imports from diff content
            Set<String> imports = extractImports(file.diffContent());
            if (!imports.isEmpty()) {
                fileImports.put(file.filePath(), imports);
            }
        }

        // Build extension-based graph
        graph.append("File extensions: ");
        graph.append(String.join(", ", extensions));
        graph.append("\n");

        // Build import-based dependencies
        if (!fileImports.isEmpty()) {
            graph.append("Dependencies:\n");
            for (Map.Entry<String, Set<String>> entry : fileImports.entrySet()) {
                graph.append("  ").append(entry.getKey()).append(" imports: ");
                graph.append(String.join(", ", entry.getValue()));
                graph.append("\n");
            }
        }

        return graph.toString();
    }

    /**
     * Extracts Java import statements from diff content.
     */
    private Set<String> extractImports(String diffContent) {
        Set<String> imports = new LinkedHashSet<>();
        if (diffContent == null) {
            return imports;
        }

        Matcher matcher = IMPORT_PATTERN.matcher(diffContent);
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports;
    }

    /**
     * Gets the file extension from a file path.
     */
    private String getFileExtension(String filePath) {
        if (filePath == null) {
            return null;
        }
        int lastDot = filePath.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        return filePath.substring(lastDot);
    }

    /**
     * Internal result holder for parsed diff content.
     */
    private record DiffParserResult(
            List<ChangedFile> changedFiles,
            List<DiffBlock> diffBlocks
    ) {}
}
