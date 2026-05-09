package com.ai.code.review.agent.security;

import com.ai.code.review.agent.ReviewAgent;
import com.ai.code.review.model.*;
import com.ai.code.review.tool.SpotBugsTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Security review agent that analyzes code changes for security vulnerabilities.
 *
 * Uses Spring AI ChatClient with a security-expert system prompt to identify
 * issues such as SQL injection, XSS, command injection, path traversal,
 * hardcoded secrets, insecure cryptography, authentication flaws, and
 * dependency vulnerabilities. Supplements LLM analysis with SpotBugs
 * static analysis when available.
 */
@Component
public class SecurityAgent implements ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(SecurityAgent.class);

    private final ChatClient chatClient;
    private final SpotBugsTool spotBugsTool;

    public SecurityAgent(ChatClient chatClient,
                         @Autowired(required = false) SpotBugsTool spotBugsTool) {
        this.chatClient = chatClient;
        this.spotBugsTool = spotBugsTool;
    }

    @Override
    public String getAgentType() {
        return "security";
    }

    @Override
    public List<ReviewResult> review(CodeContext context) {
        log.debug("SecurityAgent reviewing PR: {}", context.prId());

        try {
            List<ReviewResult> results = new ArrayList<>();

            // Get LLM-based review findings (may return multiple)
            results.addAll(callLlmForReview(context));

            // Supplement with SpotBugs static analysis
            results.addAll(runSpotBugsAnalysis(context));

            return results;

        } catch (Exception e) {
            log.error("SecurityAgent failed for PR {}: {}", context.prId(), e.getMessage(), e);
            return List.of(new ReviewResult(
                    getAgentType(),
                    ReviewSeverity.INFO,
                    "security-review",
                    "",
                    0,
                    0,
                    "Security review failed",
                    "Unable to complete security review due to an internal error: " + e.getMessage(),
                    ""
            ));
        }
    }

    /**
     * Calls the LLM via ChatClient to perform a security-focused code review.
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
     * Builds the system prompt that establishes the security expert role.
     */
    private String buildSystemPrompt() {
        return """
                You are a world-class cybersecurity expert specializing in secure code review.
                Your role is to analyze code changes for security vulnerabilities with precision.

                Focus on these vulnerability categories:
                - SQL Injection: Unsanitized user input concatenated into SQL queries
                - Cross-Site Scripting (XSS): Unsafe rendering of user-controlled data in HTML/JS
                - Command Injection: Unsanitized input used in OS command execution
                - Path Traversal: Unsafe file path operations with user input
                - Hardcoded Secrets: API keys, passwords, tokens, or certificates in source code
                - Insecure Cryptography: Weak algorithms (MD5, SHA-1, DES), improper key management
                - Authentication/Authorization Flaws: Missing permission checks, weak session handling
                - Dependency Vulnerabilities: Known vulnerable library versions in build files
                - Security Misconfiguration: Permissive CORS, missing security headers, debug endpoints
                - Insecure Deserialization: Unsafe deserialization of untrusted data

                For ALL security vulnerabilities you identify, return a list of structured results.
                Each result must include the affected file path (from the diff), line numbers,
                a clear title, detailed description including potential impact, and a specific
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
        sb.append("Review the following pull request for security vulnerabilities.\n");
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
     * Runs SpotBugs static analysis on all changed files.
     */
    private List<ReviewResult> runSpotBugsAnalysis(CodeContext context) {
        List<ReviewResult> results = new ArrayList<>();
        if (spotBugsTool == null || context.changedFiles() == null) {
            return results;
        }

        for (ChangedFile file : context.changedFiles()) {
            try {
                List<SpotBugsTool.BugPattern> patterns = spotBugsTool.analyze(
                        file.filePath(), file.diffContent());

                for (SpotBugsTool.BugPattern pattern : patterns) {
                    ReviewSeverity severity = mapSpotBugsSeverity(pattern.pattern());
                    results.add(new ReviewResult(
                            getAgentType(),
                            severity,
                            "static-analysis",
                            pattern.filePath(),
                            pattern.lineNumber(),
                            pattern.lineNumber(),
                            pattern.pattern(),
                            pattern.description(),
                            "Review and fix the identified code issue."
                    ));
                }
            } catch (Exception e) {
                log.warn("SpotBugs analysis failed for {}: {}", file.filePath(), e.getMessage());
            }
        }

        return results;
    }

    /**
     * Maps SpotBugs pattern identifiers to review severity levels.
     */
    private ReviewSeverity mapSpotBugsSeverity(String pattern) {
        return switch (pattern) {
            case "EMPTY_CATCH_BLOCK", "RESOURCE_LEAK", "RETURN_IN_VOID_METHOD" -> ReviewSeverity.MAJOR;
            case "STRING_COMPARISON_USING_EQ", "PRINT_STACK_TRACE" -> ReviewSeverity.MINOR;
            default -> ReviewSeverity.INFO;
        };
    }

    /**
     * Creates a default result when no security issues are found.
     */
    private ReviewResult createNoIssuesResult() {
        return new ReviewResult(
                getAgentType(),
                ReviewSeverity.INFO,
                "security-review",
                "",
                0,
                0,
                "No security issues found",
                "The code changes appear to be free of security vulnerabilities based on automated analysis.",
                ""
        );
    }
}
