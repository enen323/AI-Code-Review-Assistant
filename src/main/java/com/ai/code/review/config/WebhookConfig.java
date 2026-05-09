package com.ai.code.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "github")
public record WebhookConfig(Api api) {

    public record Api(
            String token,
            Webhook webhook,
            Proxy proxy
    ) {
        public record Webhook(String secret) {}
        public record Proxy(String host, String port) {}
    }
}
