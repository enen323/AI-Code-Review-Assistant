package com.ai.code.review.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST controller for collecting user feedback on review results.
 *
 * Provides a webhook endpoint that GitHub issue-comment events can POST to.
 * The comment body is parsed to extract which rule was accepted or dismissed.
 *
 * Expected comment format:
 *   /feedback (accept|dismiss) <ruleCategory> [filePath] [lineStart]
 *
 * Examples:
 *   /feedback dismiss SQL_INJECTION
 *   /feedback accept NULL_POINTER src/main/java/com/example/Service.java 42
 */
@RestController
public class FeedbackCollector {

    private static final Logger log = LoggerFactory.getLogger(FeedbackCollector.class);

    private static final Pattern FEEDBACK_PATTERN =
            Pattern.compile(
                    "^/feedback\\s+(accept|dismiss)\\s+(\\S+)(?:\\s+(\\S+))?(?:\\s+(\\d+))?\\s*$",
                    Pattern.CASE_INSENSITIVE
            );

    private final MemoryService memoryService;

    public FeedbackCollector(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * Webhook endpoint for GitHub issue comment events.
     * Expects a GitHub webhook payload with comment body and PR/repo context.
     *
     * @param payload the raw JSON payload from GitHub
     * @return HTTP response indicating success or failure
     */
    @PostMapping("/webhook/feedback")
    public ResponseEntity<String> handleFeedbackWebhook(@RequestBody String payload) {
        log.debug("Received feedback webhook payload");

        try {
            // Parse the GitHub comment webhook payload
            GitHubCommentPayload parsed = parsePayload(payload);
            if (parsed == null) {
                return ResponseEntity.badRequest().body("Unable to parse webhook payload");
            }

            String commentBody = parsed.comment() != null ? parsed.comment().body() : null;
            if (commentBody == null || commentBody.isBlank()) {
                return ResponseEntity.badRequest().body("Comment body is empty");
            }

            // Extract feedback directive from comment
            FeedbackDirective directive = extractFeedbackDirective(commentBody);
            if (directive == null) {
                return ResponseEntity.badRequest().body(
                        "Comment does not match expected feedback format. "
                        + "Use: /feedback (accept|dismiss) <ruleCategory> [filePath] [lineStart]");
            }

            // Determine repo and PR context
            String repoName = parsed.repository() != null ? parsed.repository().fullName() : "unknown";
            int prNumber = parsed.issue() != null ? parsed.issue().number() : 0;
            String prId = repoName + "#" + prNumber;

            // Build and store feedback record
            FeedbackRecord record = new FeedbackRecord(
                    prId,
                    directive.filePath(),
                    directive.lineStart(),
                    directive.ruleCategory(),
                    directive.agentType(),
                    directive.codeSnippet(),
                    directive.feedback()
            );

            memoryService.storeFeedback(record);

            log.info("Feedback recorded: {} rule '{}' for PR {} (prId={})",
                    directive.feedback(), directive.ruleCategory(), prNumber, prId);

            return ResponseEntity.ok("Feedback recorded successfully");

        } catch (Exception e) {
            log.error("Failed to process feedback webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to process feedback: " + e.getMessage());
        }
    }

    /**
     * Programmatic method to record feedback without going through the webhook.
     * Useful for testing or direct service invocation.
     *
     * @param repoName     the repository name (e.g., "owner/repo")
     * @param prNumber     the pull request number
     * @param commentBody  the comment body containing the feedback directive
     */
    public void recordFeedback(String repoName, int prNumber, String commentBody) {
        FeedbackDirective directive = extractFeedbackDirective(commentBody);
        if (directive == null) {
            throw new IllegalArgumentException("Comment does not match expected feedback format: " + commentBody);
        }

        String prId = repoName + "#" + prNumber;
        FeedbackRecord record = new FeedbackRecord(
                prId,
                directive.filePath(),
                directive.lineStart(),
                directive.ruleCategory(),
                directive.agentType(),
                directive.codeSnippet(),
                directive.feedback()
        );

        memoryService.storeFeedback(record);
        log.info("Feedback recorded programmatically: {} rule '{}' for PR {}",
                directive.feedback(), directive.ruleCategory(), prId);
    }

    /**
     * Parses the GitHub comment webhook JSON payload.
     */
    private GitHubCommentPayload parsePayload(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, GitHubCommentPayload.class);
        } catch (Exception e) {
            log.warn("Failed to parse GitHub webhook payload: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a feedback directive from a comment body.
     *
     * @param commentBody the comment text
     * @return parsed directive, or null if the format doesn't match
     */
    FeedbackDirective extractFeedbackDirective(String commentBody) {
        if (commentBody == null || commentBody.isBlank()) {
            return null;
        }

        // Try the structured format first
        Matcher matcher = FEEDBACK_PATTERN.matcher(commentBody.trim());
        if (matcher.matches()) {
            FeedbackType feedback = matcher.group(1).equalsIgnoreCase("accept")
                    ? FeedbackType.ACCEPTED : FeedbackType.DISMISSED;
            String ruleCategory = matcher.group(2);
            String filePath = matcher.group(3);
            int lineStart = 0;
            if (matcher.group(4) != null) {
                lineStart = Integer.parseInt(matcher.group(4));
            }

            // The agent type is derived from the rule category prefix if available,
            // otherwise defaults to "unknown"
            String agentType = deriveAgentType(ruleCategory);

            return new FeedbackDirective(feedback, ruleCategory, agentType, filePath, lineStart, null);
        }

        return null;
    }

    /**
     * Derives the agent type from a rule category string.
     * Convention: rule categories prefixed with agent type, e.g., "security/SQL_INJECTION"
     * or mapped from known patterns.
     */
    private String deriveAgentType(String ruleCategory) {
        if (ruleCategory == null) return "unknown";

        String upper = ruleCategory.toUpperCase();
        if (upper.contains("SQL") || upper.contains("XSS") || upper.contains("SECURITY")
                || upper.contains("INJECTION") || upper.contains("AUTH")) {
            return "security";
        }
        if (upper.contains("NULL") || upper.contains("NPE") || upper.contains("LOGIC")
                || upper.contains("CONDITION") || upper.contains("BOUND")) {
            return "logic";
        }
        if (upper.contains("STYLE") || upper.contains("FORMAT") || upper.contains("NAMING")
                || upper.contains("CONVENTION")) {
            return "codestyle";
        }
        if (upper.contains("ARCH") || upper.contains("LAYER") || upper.contains("CIRCULAR")
                || upper.contains("DEPENDENCY") || upper.contains("COUPLING")) {
            return "architecture";
        }

        return "unknown";
    }

    /**
     * Internal record representing a parsed feedback directive.
     */
    record FeedbackDirective(
            FeedbackType feedback,
            String ruleCategory,
            String agentType,
            String filePath,
            int lineStart,
            String codeSnippet
    ) {}

    /**
     * Lightweight DTO for GitHub issue comment webhook payload.
     * Only maps the fields needed for feedback collection.
     */
    static class GitHubCommentPayload {

        private String action;
        private Issue issue;
        private Comment comment;
        private Repository repository;

        public String action() { return action; }
        public Issue issue() { return issue; }
        public Comment comment() { return comment; }
        public Repository repository() { return repository; }

        @JsonProperty("action")
        public void setAction(String action) { this.action = action; }

        @JsonProperty("issue")
        public void setIssue(Issue issue) { this.issue = issue; }

        @JsonProperty("comment")
        public void setComment(Comment comment) { this.comment = comment; }

        @JsonProperty("repository")
        public void setRepository(Repository repository) { this.repository = repository; }

        static class Issue {
            private int number;

            public int number() { return number; }

            @JsonProperty("number")
            public void setNumber(int number) { this.number = number; }
        }

        static class Comment {
            private String body;

            public String body() { return body; }

            @JsonProperty("body")
            public void setBody(String body) { this.body = body; }
        }

        static class Repository {
            private String fullName;

            public String fullName() { return fullName; }

            @JsonProperty("full_name")
            public void setFullName(String fullName) { this.fullName = fullName; }
        }
    }
}
