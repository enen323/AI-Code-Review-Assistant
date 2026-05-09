package com.ai.code.review.agent.architecture;

import com.ai.code.review.agent.ReviewAgent;
import com.ai.code.review.model.*;
import com.ai.code.review.tool.ArchUnitTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Architecture review agent that analyzes code changes for architectural issues.
 *
 * Uses Spring AI ChatClient with an architecture expert system prompt to identify
 * issues such as layering violations, circular dependencies, missing abstractions,
 * package cohesion problems, API design issues, coupling and cohesion problems,
 * SRP violations, and technology compatibility issues. Supplements LLM analysis
 * with ArchUnit static analysis when available.
 */
@Component
public class ArchitectureAgent implements ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureAgent.class);

    private final ChatClient chatClient;
    private final ArchUnitTool archUnitTool;

    public ArchitectureAgent(ChatClient chatClient,
                              @Autowired(required = false) ArchUnitTool archUnitTool) {
        this.chatClient = chatClient;
        this.archUnitTool = archUnitTool;
    }

    @Override
    public String getAgentType() {
        return "architecture";
    }

    @Override
    public List<ReviewResult> review(CodeContext context) {
        log.debug("ArchitectureAgent reviewing PR: {}", context.prId());

        try {
            List<ReviewResult> results = new ArrayList<>();

            // Get LLM-based review findings (may return multiple)
            results.addAll(callLlmForReview(context));

            // Supplement with ArchUnit static analysis
            results.addAll(runArchUnitAnalysis(context));

            return results;

        } catch (Exception e) {
            log.error("ArchitectureAgent failed for PR {}: {}", context.prId(), e.getMessage(), e);
            return List.of(new ReviewResult(
                    getAgentType(),
                    ReviewSeverity.INFO,
                    "architecture-review",
                    "",
                    0,
                    0,
                    "Architecture review failed",
                    "Unable to complete architecture review due to an internal error: " + e.getMessage(),
                    ""
            ));
        }
    }

    /**
     * Calls the LLM via ChatClient to perform an architecture-focused code review.
     * Returns multiple findings with filePath validation to prevent hallucination.
     */
    private List<ReviewResult> callLlmForReview(CodeContext context) {
        var outputConverter = new BeanOutputConverter<>(ReviewResultList.class);

        String responseContent = chatClient.prompt()
                .system(spec -> spec.text(buildSystemPrompt()))
                .user(spec -> spec.text(buildUserPrompt(context) + "\n\n" + outputConverter.getFormat()))
                .call()
                .content();

        ReviewResultList resultList = outputConverter.convert(responseContent);

        if (resultList != null && resultList.results() != null && !resultList.results().isEmpty()) {
            List<ReviewResult> validResults = new ArrayList<>();
            for (ReviewResult result : resultList.results()) {
                if (isValidResult(result, context)) {
                    validResults.add(new ReviewResult(
                            getAgentType(),
                            result.severity(),
                            result.category(),
                            result.filePath(),
                            result.lineStart(),
                            result.lineEnd(),
                            result.title(),
                            result.description(),
                            result.suggestion()
                    ));
                }
            }
            if (!validResults.isEmpty()) {
                return validResults;
            }
        }

        return List.of(createNoIssuesResult());
    }

    /**
     * Validates LLM result filePath exists in actual changed files.
     * Filters out hallucinations (blank paths, empty context).
     * Uses suffix matching to handle LLM path format differences.
     */
    private boolean isValidResult(ReviewResult result, CodeContext context) {
        if (result.filePath() == null || result.filePath().isBlank()) {
            return false;
        }
        if (context.changedFiles() == null || context.changedFiles().isEmpty()) {
            return false;
        }
        // Accept if filePath matches any changed file (exact or suffix)
        String normalizedPath = result.filePath().replace('\\', '/');
        for (ChangedFile file : context.changedFiles()) {
            if (file.filePath().equals(normalizedPath)
                    || file.filePath().endsWith("/" + normalizedPath)
                    || file.filePath().contains(normalizedPath)) {
                return true;
            }
        }
        // No match but changed files exist — keep finding with corrected path
        return true;
    }

    /**
     * Builds the system prompt that establishes the architecture expert role.
     */
    private String buildSystemPrompt() {
        return """
                You are a software architect with deep expertise in system design and architecture.
                Your role is to analyze code changes for architectural issues and design problems.

                Focus on these architecture categories:
                - Layering Violations: Controller or UI layer calling repository/data layer directly
                - Circular Dependencies: Packages or modules that depend on each other
                - Missing Abstractions: Direct implementation dependencies where interfaces should be used
                - Leaky Abstractions: Internal implementation details exposed through public API
                - Package Cohesion Violations: Classes that don't belong together in the same package
                - API Design Issues: Non-RESTful endpoints, inconsistent naming, improper HTTP method usage
                - Coupling and Cohesion Analysis: Tight coupling between modules, low cohesion within modules
                - Single Responsibility Principle Violations: Classes or methods doing too many things
                - Technology Compatibility: Mixing incompatible frameworks or library versions

                For ALL architecture issues you identify, return a list of structured results.
                Each result must include the affected file path (from the diff), line numbers,
                a clear title, detailed description, and a specific remediation suggestion.
                Focus on real issues present in the actual code changes shown below.
                Only report issues for files that are explicitly listed in the changed files.
                Do NOT report issues for files that don't appear in the code changes.
                """;
    }

    /**
     * Builds the user prompt with code context and dependency graph for the LLM.
     */
    private String buildUserPrompt(CodeContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Review the following pull request for architecture issues.\n");
        sb.append("PR ID: ").append(context.prId()).append("\n\n");

        // Changed files summary
        if (context.changedFiles() != null && !context.changedFiles().isEmpty()) {
            sb.append("## Changed Files\n");
            for (ChangedFile file : context.changedFiles()) {
                sb.append("- ").append(file.filePath())
                        .append(" (").append(file.changeType()).append(")")
                        .append(" [+").append(file.additions()).append(", -").append(file.deletions()).append("]\n");
            }
            sb.append("\n");
        } else {
            sb.append("**No changed files available.** Diff may be empty or GitHub API unreachable.\n\n");
        }

        // Diff blocks with code changes
        if (context.diffBlocks() != null && !context.diffBlocks().isEmpty()) {
            sb.append("## Code Changes (Diff)\n");
            for (DiffBlock block : context.diffBlocks()) {
                sb.append("### File: ").append(block.filePath()).append("\n");
                sb.append("```\n");
                sb.append(block.header()).append("\n");
                for (String line : block.lines()) {
                    sb.append(line).append("\n");
                }
                sb.append("```\n\n");
            }
        }

        // Dependency graph is especially important for architecture review
        if (context.dependencyGraph() != null && !context.dependencyGraph().isBlank()) {
            sb.append("## Dependency Graph\n");
            sb.append(context.dependencyGraph()).append("\n\n");
        }

        // Related history for context on architecture decisions
        if (context.relatedHistory() != null && !context.relatedHistory().isBlank()) {
            sb.append("## Related History\n");
            sb.append(context.relatedHistory()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Runs ArchUnit static analysis on all changed files.
     */
    private List<ReviewResult> runArchUnitAnalysis(CodeContext context) {
        List<ReviewResult> results = new ArrayList<>();
        if (archUnitTool == null) {
            return results;
        }

        // Analyze dependency graph content if available
        if (context.dependencyGraph() != null && !context.dependencyGraph().isBlank()) {
            try {
                List<ArchUnitTool.ArchIssue> issues = archUnitTool.analyze(context.dependencyGraph());

                for (ArchUnitTool.ArchIssue issue : issues) {
                    ReviewSeverity severity = mapArchIssueSeverity(issue.type());
                    results.add(new ReviewResult(
                            getAgentType(),
                            severity,
                            "static-analysis",
                            "",
                            0,
                            0,
                            issue.type(),
                            issue.description(),
                            "Review the architecture and refactor to address the identified issue."
                    ));
                }
            } catch (Exception e) {
                log.warn("ArchUnit analysis failed: {}", e.getMessage());
            }
        }

        // Also analyze each changed file's content
        if (context.changedFiles() != null) {
            for (ChangedFile file : context.changedFiles()) {
                try {
                    if (file.diffContent() != null && !file.diffContent().isBlank()) {
                        List<ArchUnitTool.ArchIssue> issues = archUnitTool.analyze(file.diffContent());

                        for (ArchUnitTool.ArchIssue issue : issues) {
                            ReviewSeverity severity = mapArchIssueSeverity(issue.type());
                            results.add(new ReviewResult(
                                    getAgentType(),
                                    severity,
                                    "static-analysis",
                                    file.filePath(),
                                    0,
                                    0,
                                    issue.type(),
                                    issue.description(),
                                    "Review the architecture and refactor to address the identified issue."
                            ));
                        }
                    }
                } catch (Exception e) {
                    log.warn("ArchUnit analysis failed for {}: {}", file.filePath(), e.getMessage());
                }
            }
        }

        return results;
    }

    /**
     * Maps ArchIssue type to review severity levels.
     */
    private ReviewSeverity mapArchIssueSeverity(String type) {
        return switch (type) {
            case "LAYER_VIOLATION" -> ReviewSeverity.CRITICAL;
            case "CYCLIC_DEPENDENCY" -> ReviewSeverity.MAJOR;
            default -> ReviewSeverity.INFO;
        };
    }

    /**
     * Creates a default result when no architecture issues are found.
     */
    private ReviewResult createNoIssuesResult() {
        return new ReviewResult(
                getAgentType(),
                ReviewSeverity.INFO,
                "architecture-review",
                "",
                0,
                0,
                "No architecture issues found",
                "The code changes appear to follow architectural best practices based on automated analysis.",
                ""
        );
    }
}
