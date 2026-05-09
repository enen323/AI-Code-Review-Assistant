package com.ai.code.review.trigger;

import com.ai.code.review.model.ReviewTask;
import com.ai.code.review.model.TriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for handling GitHub webhook requests.
 * Validates the webhook signature, parses the payload, creates a ReviewTask,
 * and publishes a ReviewTaskEvent for downstream processing.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final ApplicationEventPublisher eventPublisher;
    private final String webhookSecret;

    public WebhookController(
            ApplicationEventPublisher eventPublisher,
            @Value("${github.api.webhook.secret}") String webhookSecret) {
        this.eventPublisher = eventPublisher;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/webhook/pr")
    public ResponseEntity<String> handlePrWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader) {

        if (!WebhookSignatureValidator.validate(payload, signatureHeader, webhookSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Signature mismatch");
        }

        GitHubWebhookPayload webhookPayload;
        try {
            webhookPayload = GitHubWebhookPayload.fromJson(payload);
        } catch (Exception e) {
            log.warn("Failed to parse webhook payload: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid payload");
        }

        TriggerEvent triggerEvent = mapActionToTriggerEvent(webhookPayload.action());
        if (triggerEvent == null) {
            return ResponseEntity.badRequest().body("Unsupported action: " + webhookPayload.action());
        }

        ReviewTask task = buildReviewTask(webhookPayload, triggerEvent);
        eventPublisher.publishEvent(new ReviewTaskEvent(task));

        return ResponseEntity.ok("Webhook processed successfully");
    }

    @PostMapping("/webhook/push")
    public ResponseEntity<String> handlePushWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader) {

        if (!WebhookSignatureValidator.validate(payload, signatureHeader, webhookSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Signature mismatch");
        }

        GitHubPushPayload pushPayload;
        try {
            pushPayload = GitHubPushPayload.fromJson(payload);
        } catch (Exception e) {
            log.warn("Failed to parse push webhook payload: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid payload");
        }

        // Skip deletion events
        if (pushPayload.after() == null || pushPayload.after().matches("0+")) {
            return ResponseEntity.ok("Delete event, skipping");
        }

        // Skip new branch pushes (no diff to compare)
        if (pushPayload.isNewBranch()) {
            return ResponseEntity.ok("New branch event, skipping");
        }

        // Skip tag pushes
        if (pushPayload.ref() != null && pushPayload.ref().startsWith("refs/tags/")) {
            return ResponseEntity.ok("Tag push event, skipping");
        }

        ReviewTask task = buildPushReviewTask(pushPayload);
        eventPublisher.publishEvent(new ReviewTaskEvent(task));

        return ResponseEntity.ok("Push webhook processed successfully");
    }

    /**
     * Maps GitHub action strings to TriggerEvent enums.
     */
    private TriggerEvent mapActionToTriggerEvent(String action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case "opened" -> TriggerEvent.PR_OPENED;
            case "synchronize" -> TriggerEvent.PR_SYNCHRONIZED;
            case "closed" -> TriggerEvent.PR_CLOSED;
            default -> null;
        };
    }

    /**
     * Builds a ReviewTask from the parsed webhook payload.
     */
    private ReviewTask buildReviewTask(GitHubWebhookPayload payload, TriggerEvent triggerEvent) {
        GitHubWebhookPayload.PullRequest pr = payload.pullRequest();
        if (pr == null) {
            throw new IllegalArgumentException("pullRequest is null in webhook payload");
        }
        String prId = payload.repository().fullName() + "#" + payload.number();

        return new ReviewTask(
            prId,
            payload.repository().fullName(),
            payload.number(),
            pr.title() != null ? pr.title() : "",
            pr.body() != null ? pr.body() : "",
            pr.head() != null ? pr.head().ref() : "",
            pr.base() != null ? pr.base().ref() : "",
            pr.head() != null ? pr.head().sha() : "",
            pr.diffUrl() != null ? pr.diffUrl() : "",
            triggerEvent
        );
    }

    /**
     * Builds a ReviewTask from the parsed push webhook payload.
     */
    private ReviewTask buildPushReviewTask(GitHubPushPayload payload) {
        String sha = payload.after() != null ? payload.after() : "unknown";
        String prId = payload.repository().fullName() + "#push:" + sha;
        String commitMessage = payload.headCommit() != null ? payload.headCommit().message() : "";

        return new ReviewTask(
            prId,
            payload.repository().fullName(),
            0,
            "Push: " + payload.branchName(),
            commitMessage,
            payload.branchName(),
            "",
            sha,
            payload.compareDiffUrl(),
            TriggerEvent.PUSH
        );
    }
}
