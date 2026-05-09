package com.ai.code.review.agent.logic;

import com.ai.code.review.agent.ReviewAgent;
import com.ai.code.review.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Logic review agent that analyzes code changes for logic errors and bugs.
 *
 * Uses Spring AI ChatClient with a senior developer system prompt focused on
 * bug detection to identify issues such as null pointer risks, off-by-one errors,
 * race conditions, incorrect exception handling, resource leaks, incorrect
 * conditional logic, and infinite loops.
 */
@Component
public class LogicAgent implements ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(LogicAgent.class);

    private final ChatClient chatClient;

    public LogicAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String getAgentType() {
        return "logic";
    }

    @Override
    public List<ReviewResult> review(CodeContext context) {
        log.debug("LogicAgent reviewing PR: {}", context.prId());

        try {
            return callLlmForReview(context);
        } catch (Exception e) {
            log.error("LogicAgent failed for PR {}: {}", context.prId(), e.getMessage(), e);
            return List.of(new ReviewResult(
                    getAgentType(),
                    ReviewSeverity.INFO,
                    "logic-review",
                    "",
                    0,
                    0,
                    "Logic review failed",
                    "Unable to complete logic review due to an internal error: " + e.getMessage(),
                    ""
            ));
        }
    }

    /**
     * Calls the LLM via ChatClient to perform a logic-focused code review.
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
     * Builds the system prompt that establishes the senior developer role
     * focused on bug detection.
     */
    private String buildSystemPrompt() {
        return """
                You are a senior software engineer with deep expertise in code review and bug detection.
                Your role is to analyze code changes and identify logic errors and potential bugs.

                Focus on these bug categories:
                - Null Pointer Risks: Dereferencing objects without null checks
                - Off-by-One Errors: Incorrect loop bounds or array indices
                - Race Conditions: Shared mutable state without proper synchronization
                - Incorrect Exception Handling: Swallowing exceptions, overly broad catches
                - Resource Leaks: Unclosed streams, connections, or other resources
                - Incorrect Conditional Logic: Wrong operators, missing cases, tautologies
                - Infinite Loops: Loop conditions that never terminate
                - Data Integrity Issues: Incorrect data transformations or type conversions
                - Concurrency Bugs: Deadlocks, livelocks, thread safety violations
                - API Misuse: Incorrect method signatures, wrong parameter ordering
                - Java Compile-time Errors: Returning value in void method, missing return statements,
                  unreachable code, type mismatch, unhandled checked exceptions

                For ALL bugs you identify, return a list of structured results.
                Each result must include the affected file path (from the diff), line numbers,
                a clear title, detailed description including how it manifests, and a specific
                code-level remediation suggestion. Focus on real issues present in the actual
                code changes. Only report issues for files explicitly listed in the changed files.
                Do NOT report issues for files that don't appear in the code changes.
                """;
    }

    /**
     * Builds the user prompt with code context for the LLM.
     */
    private String buildUserPrompt(CodeContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Review the following pull request for logic errors and bugs.\n");
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
     * Creates a default result when no logic issues are found.
     */
    private ReviewResult createNoIssuesResult() {
        return new ReviewResult(
                getAgentType(),
                ReviewSeverity.INFO,
                "logic-review",
                "",
                0,
                0,
                "No logic issues found",
                "The code changes appear to be logically correct based on automated analysis.",
                ""
        );
    }
}
