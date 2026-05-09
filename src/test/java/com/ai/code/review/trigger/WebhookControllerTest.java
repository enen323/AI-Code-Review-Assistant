package com.ai.code.review.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for WebhookController using standalone MockMvc setup.
 */
class WebhookControllerTest {

    private MockMvc mockMvc;
    private ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TEST_SECRET = "test_webhook_secret";

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        WebhookController controller = new WebhookController(eventPublisher, TEST_SECRET);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * Tests that a valid webhook request with PR_OPENED action returns 200 OK.
     */
    @Test
    void testValidWebhookPrOpened() throws Exception {
        String payload = createWebhookPayload("opened");
        String signature = computeHmacSha256(payload, TEST_SECRET);

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + signature)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("Webhook processed successfully"));

        verify(eventPublisher, times(1)).publishEvent(any(ReviewTaskEvent.class));
    }

    /**
     * Tests that a valid webhook request with PR_SYNCHRONIZED action returns 200 OK.
     */
    @Test
    void testValidWebhookPrSynchronize() throws Exception {
        String payload = createWebhookPayload("synchronize");
        String signature = computeHmacSha256(payload, TEST_SECRET);

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + signature)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("Webhook processed successfully"));

        verify(eventPublisher, times(1)).publishEvent(any(ReviewTaskEvent.class));
    }

    /**
     * Tests that a valid webhook request with PR_CLOSED action returns 200 OK.
     */
    @Test
    void testValidWebhookPrClosed() throws Exception {
        String payload = createWebhookPayload("closed");
        String signature = computeHmacSha256(payload, TEST_SECRET);

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + signature)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("Webhook processed successfully"));

        verify(eventPublisher, times(1)).publishEvent(any(ReviewTaskEvent.class));
    }

    /**
     * Tests that a request with an invalid signature returns 401.
     */
    @Test
    void testInvalidSignatureReturns401() throws Exception {
        String payload = createWebhookPayload("opened");

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=invalidhexsignature")
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Signature mismatch"));

        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * Tests that a request with a missing signature header returns 401.
     */
    @Test
    void testMissingSignatureHeaderReturns401() throws Exception {
        String payload = createWebhookPayload("opened");

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Signature mismatch"));

        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * Tests that a request with an unsupported action returns 400.
     */
    @Test
    void testUnsupportedActionReturns400() throws Exception {
        String payload = createWebhookPayload("labeled");
        String signature = computeHmacSha256(payload, TEST_SECRET);

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + signature)
                        .content(payload))
                .andExpect(status().isBadRequest());

        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * Tests that a request with an invalid JSON payload returns 400.
     */
    @Test
    void testInvalidPayloadReturns400() throws Exception {
        String invalidPayload = "this is not json";
        String signature = computeHmacSha256(invalidPayload, TEST_SECRET);

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + signature)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());

        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * Creates a sample GitHub webhook JSON payload.
     */
    private String createWebhookPayload(String action) throws Exception {
        return objectMapper.writeValueAsString(
                new GitHubWebhookPayload(
                        action,
                        new GitHubWebhookPayload.PullRequest(
                                "Test PR",
                                "This is a test PR description",
                                new GitHubWebhookPayload.GitRef("feature-branch", "abc123def456"),
                                new GitHubWebhookPayload.GitRef("main", "789012ghi345"),
                                "https://api.github.com/repos/owner/repo/pulls/1"
                        ),
                        new GitHubWebhookPayload.Repository("owner/repo"),
                        1
                )
        );
    }

    /**
     * Computes HMAC-SHA256 signature for testing.
     */
    private String computeHmacSha256(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
        mac.init(keySpec);
        byte[] hmacBytes = mac.doFinal(payload.getBytes());
        return HexFormat.of().formatHex(hmacBytes);
    }
}
