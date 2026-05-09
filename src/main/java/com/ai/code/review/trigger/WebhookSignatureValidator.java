package com.ai.code.review.trigger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility for validating GitHub webhook signatures using HMAC-SHA256.
 */
public class WebhookSignatureValidator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    /**
     * Validates the HMAC-SHA256 signature of a webhook payload.
     *
     * @param payload         the raw request body
     * @param signatureHeader the value of the X-Hub-Signature-256 header
     * @param secret          the shared webhook secret
     * @return true if the signature is valid, false otherwise
     */
    public static boolean validate(String payload, String signatureHeader, String secret) {
        if (payload == null || signatureHeader == null || secret == null) {
            return false;
        }

        String expectedSignature = computeHmacSha256(payload, secret);
        if (expectedSignature == null) {
            return false;
        }

        String providedSignature = stripSignaturePrefix(signatureHeader);
        if (providedSignature == null) {
            return false;
        }

        return constantTimeEquals(expectedSignature, providedSignature);
    }

    /**
     * Computes HMAC-SHA256 hex digest of the payload using the given secret.
     */
    private static String computeHmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return null;
        }
    }

    /**
     * Strips the "sha256=" prefix from the signature header value.
     */
    private static String stripSignaturePrefix(String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return null;
        }
        return signatureHeader.substring(SIGNATURE_PREFIX.length());
    }

    /**
     * Performs a constant-time comparison of two hex strings to prevent timing attacks.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
