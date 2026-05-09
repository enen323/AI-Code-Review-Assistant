package com.ai.code.review.trigger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebhookSignatureValidator.
 */
class WebhookSignatureValidatorTest {

    private static final String TEST_SECRET = "my_webhook_secret";
    private static final String TEST_PAYLOAD = "{\"action\":\"opened\",\"number\":1}";

    /**
     * Verifies that a valid signature is accepted.
     */
    @Test
    void testValidSignature() {
        // The expected HMAC-SHA256 hex for payload "{\"action\":\"opened\",\"number\":1}"
        // with secret "my_webhook_secret". We compute it programmatically to avoid hardcoding.
        String expectedSignature = computeExpectedSignature(TEST_PAYLOAD, TEST_SECRET);
        String signatureHeader = "sha256=" + expectedSignature;

        assertTrue(WebhookSignatureValidator.validate(TEST_PAYLOAD, signatureHeader, TEST_SECRET));
    }

    /**
     * Verifies that an invalid signature is rejected.
     */
    @Test
    void testInvalidSignature() {
        String signatureHeader = "sha256=invalidsignaturehexstring1234567890abcdef1234567890abcdef";

        assertFalse(WebhookSignatureValidator.validate(TEST_PAYLOAD, signatureHeader, TEST_SECRET));
    }

    /**
     * Verifies that a tampered payload is rejected.
     */
    @Test
    void testTamperedPayload() {
        String validSignature = computeExpectedSignature(TEST_PAYLOAD, TEST_SECRET);
        String signatureHeader = "sha256=" + validSignature;

        // Use a different payload
        assertFalse(WebhookSignatureValidator.validate(
                "{\"action\":\"closed\",\"number\":1}",
                signatureHeader,
                TEST_SECRET));
    }

    /**
     * Verifies that different secrets produce different signatures.
     */
    @Test
    void testWrongSecret() {
        String validSignature = computeExpectedSignature(TEST_PAYLOAD, TEST_SECRET);
        String signatureHeader = "sha256=" + validSignature;

        assertFalse(WebhookSignatureValidator.validate(
                TEST_PAYLOAD,
                signatureHeader,
                "different_secret"));
    }

    /**
     * Verifies that null payload returns false.
     */
    @Test
    void testNullPayload() {
        assertFalse(WebhookSignatureValidator.validate(null, "sha256=abc", TEST_SECRET));
    }

    /**
     * Verifies that null signature header returns false.
     */
    @Test
    void testNullSignatureHeader() {
        assertFalse(WebhookSignatureValidator.validate(TEST_PAYLOAD, null, TEST_SECRET));
    }

    /**
     * Verifies that null secret returns false.
     */
    @Test
    void testNullSecret() {
        assertFalse(WebhookSignatureValidator.validate(TEST_PAYLOAD, "sha256=abc", null));
    }

    /**
     * Verifies that signature without proper prefix is rejected.
     */
    @Test
    void testSignatureWithoutPrefix() {
        assertFalse(WebhookSignatureValidator.validate(
                TEST_PAYLOAD, "abc123", TEST_SECRET));
    }

    /**
     * Computes expected HMAC-SHA256 hex string for testing.
     */
    private String computeExpectedSignature(String payload, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec =
                    new javax.crypto.spec.SecretKeySpec(secret.getBytes(), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes());
            return java.util.HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }
}
