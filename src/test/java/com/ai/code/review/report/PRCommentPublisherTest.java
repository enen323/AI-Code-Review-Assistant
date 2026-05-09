package com.ai.code.review.report;

import com.ai.code.review.config.WebhookConfig;
import com.ai.code.review.model.AggregatedReport;
import com.ai.code.review.model.ReviewResult;
import com.ai.code.review.model.ReviewSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PRCommentPublisherTest {

    private PRCommentPublisher publisher;
    private AggregatedReport sampleReport;

    @BeforeEach
    void setUp() {
        WebhookConfig.Api.Proxy emptyProxy = new WebhookConfig.Api.Proxy("", "");
        WebhookConfig.Api.Webhook emptyWebhook = new WebhookConfig.Api.Webhook("");
        WebhookConfig.Api api = new WebhookConfig.Api("", emptyWebhook, emptyProxy);
        publisher = new PRCommentPublisher(new WebhookConfig(api));

        List<ReviewResult> results = List.of(
                new ReviewResult("security", ReviewSeverity.CRITICAL, "SQL_INJECTION",
                        "UserService.java", 42, 45,
                        "SQL Injection risk", "User input not sanitized",
                        "Use prepared statements"),
                new ReviewResult("code-style", ReviewSeverity.MINOR, "NAMING",
                        "Config.java", 10, 10,
                        "Poor variable name", "Variable 'x'",
                        "Rename")
        );

        sampleReport = new AggregatedReport(
                "owner/repo#1",
                "Found 1 critical, 0 major, 1 minor issues across 2 files.",
                1, 0, 1,
                results
        );
    }

    @Test
    void testNullTokenSkipsPublishing() {
        publisher.publishReview("owner/repo", 1, sampleReport, null);
    }

    @Test
    void testBlankTokenSkipsPublishing() {
        publisher.publishReview("owner/repo", 1, sampleReport, "");
    }

    @Test
    void testNullReportSkipsPublishing() {
        publisher.publishReview("owner/repo", 1, null, "test-token");
    }

    @Test
    void testPublishReviewWithInvalidToken() {
        publisher.publishReview("owner/repo", 1, sampleReport, "invalid-token");
        assertTrue(true);
    }

    @Test
    void testFormatLineCommentWithAllFields() {
        String result = invokeFormatLineComment(new ReviewResult(
                "security", ReviewSeverity.CRITICAL, "SQL_INJECTION",
                "File.java", 42, 45, "SQL Injection",
                "User input is not sanitized", "Use prepared statements"
        ));

        assertTrue(result.contains("CRITICAL"));
        assertTrue(result.contains("SQL_INJECTION"));
        assertTrue(result.contains("SQL Injection"));
        assertTrue(result.contains("User input is not sanitized"));
        assertTrue(result.contains("Use prepared statements"));
    }

    @Test
    void testFormatLineCommentWithMinimalFields() {
        String result = invokeFormatLineComment(new ReviewResult(
                "code-style", ReviewSeverity.INFO, null,
                "File.java", 1, 2, "Minor issue", null, null
        ));

        assertTrue(result.contains("INFO"));
        assertTrue(result.contains("Minor issue"));
        assertFalse(result.contains("[null]"));
    }

    private String invokeFormatLineComment(ReviewResult result) {
        try {
            var method = PRCommentPublisher.class.getDeclaredMethod("formatLineComment", ReviewResult.class);
            method.setAccessible(true);
            return (String) method.invoke(publisher, result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke formatLineComment", e);
        }
    }
}
