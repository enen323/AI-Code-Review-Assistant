package com.ai.code.review.agent.codestyle;

import com.ai.code.review.agent.ReviewAgent;
import com.ai.code.review.model.*;
import com.ai.code.review.tool.CheckstyleTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Code style review agent that analyzes code changes for style and formatting issues.
 *
 * Uses Spring AI ChatClient with a code style expert system prompt to identify
 * issues such as naming convention violations, formatting inconsistencies,
 * dead code, unused imports, overly complex methods, and deviation from Java
 * best practices. Supplements LLM analysis with Checkstyle static analysis
 * when available.
 */
@Component
public class CodeStyleAgent implements ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(CodeStyleAgent.class);

    private final ChatClient chatClient;
    private final CheckstyleTool checkstyleTool;

    public CodeStyleAgent(ChatClient chatClient,
                          @Autowired(required = false) CheckstyleTool checkstyleTool) {
        this.chatClient = chatClient;
        this.checkstyleTool = checkstyleTool;
    }

    @Override
    public String getAgentType() {
        return "code-style";
    }

    @Override
    public List<ReviewResult> review(CodeContext context) {
        log.debug("CodeStyleAgent reviewing PR: {}", context.prId());

        try {
            List<ReviewResult> results = new ArrayList<>();

            // Get LLM-based review findings (may return multiple)
            results.addAll(callLlmForReview(context));

            // Supplement with Checkstyle static analysis
            results.addAll(runCheckstyleAnalysis(context));

            return results;

        } catch (Exception e) {
            log.error("CodeStyleAgent failed for PR {}: {}", context.prId(), e.getMessage(), e);
            return List.of(new ReviewResult(
                    getAgentType(),
                    ReviewSeverity.INFO,
                    "code-style-review",
                    "",
                    0,
                    0,
                    "Code style review failed",
                    "Unable to complete code style review due to an internal error: " + e.getMessage(),
                    ""
            ));
        }
    }

    /**
     * Calls the LLM via ChatClient to perform a code style-focused review.
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
        String normalizedPath = result.filePath().replace('\\', '/');
        for (ChangedFile file : context.changedFiles()) {
            if (file.filePath().equals(normalizedPath)
                    || file.filePath().endsWith("/" + normalizedPath)
                    || file.filePath().contains(normalizedPath)) {
                return true;
            }
        }
        return true;
    }

    /**
     * Builds the system prompt that establishes the code style expert role.
     */
    private String buildSystemPrompt() {
        return """
                You are a senior software engineer specializing in code quality and style.
                Your role is to analyze code changes for style issues and adherence to Java best practices.

                Focus on these code style categories:
                - Naming Conventions: camelCase for variables/methods, PascalCase for classes, UPPER_SNAKE for constants
                - Code Formatting: inconsistent indentation, brace placement, excessive line length
                - Design Patterns: over-engineering (unnecessary patterns), missing patterns where appropriate
                - Dead Code: unused imports, unused variables, unused methods, unreachable code
                - Method Length / Complexity: overly long methods, excessive cyclomatic complexity
                - Java Best Practices: use records vs classes where appropriate, proper Optional usage,
                  prefer interfaces over abstract classes, favor composition over inheritance
                - Error Prone Patterns: switch fallthrough without comment, empty return statements,
                  returning null instead of Optional, mutable collections exposure

                For ALL code style issues you identify, return a list of structured results.
                Each result must include the affected file path (from the diff), line numbers,
                a clear title, detailed description, and a specific code-level remediation suggestion.
                Focus on real issues present in the actual code changes.
                Only report issues for files explicitly listed in the changed files.
                Do NOT report issues for files that don't appear in the code changes.
                """;
    }

    /**
     * Builds the user prompt with code context for the LLM.
     */
    private String buildUserPrompt(CodeContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Review the following pull request for code style issues.\n");
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

        // Dependency context
        if (context.dependencyGraph() != null && !context.dependencyGraph().isBlank()) {
            sb.append("## Dependency Context\n");
            sb.append(context.dependencyGraph()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Runs Checkstyle static analysis on all changed files.
     */
    private List<ReviewResult> runCheckstyleAnalysis(CodeContext context) {
        List<ReviewResult> results = new ArrayList<>();
        if (checkstyleTool == null || context.changedFiles() == null) {
            return results;
        }

        for (ChangedFile file : context.changedFiles()) {
            try {
                List<CheckstyleTool.CodeStyleIssue> issues = checkstyleTool.analyze(
                        file.filePath(), file.diffContent());

                for (CheckstyleTool.CodeStyleIssue issue : issues) {
                    ReviewSeverity severity = mapCheckstyleSeverity(issue.rule());
                    results.add(new ReviewResult(
                            getAgentType(),
                            severity,
                            "static-analysis",
                            issue.filePath(),
                            issue.lineNumber(),
                            issue.lineNumber(),
                            issue.rule(),
                            issue.description(),
                            "Review and fix the identified code style issue."
                    ));
                }
            } catch (Exception e) {
                log.warn("Checkstyle analysis failed for {}: {}", file.filePath(), e.getMessage());
            }
        }

        return results;
    }

    /**
     * Maps Checkstyle rule identifiers to review severity levels.
     */
    private ReviewSeverity mapCheckstyleSeverity(String rule) {
        return switch (rule) {
            case "EmptyBlock" -> ReviewSeverity.MAJOR;
            case "MagicNumber", "MethodLength" -> ReviewSeverity.MINOR;
            case "ParameterNumber" -> ReviewSeverity.MINOR;
            default -> ReviewSeverity.INFO;
        };
    }

    /**
     * Creates a default result when no code style issues are found.
     */
    private ReviewResult createNoIssuesResult() {
        return new ReviewResult(
                getAgentType(),
                ReviewSeverity.INFO,
                "code-style-review",
                "",
                0,
                0,
                "No code style issues found",
                "The code changes appear to follow coding standards based on automated analysis.",
                ""
        );
    }
}
