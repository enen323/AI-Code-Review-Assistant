package com.ai.code.review.context;

import com.ai.code.review.config.WebhookConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final HttpClient httpClient;
    private final String apiToken;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    public GitHubClient(WebhookConfig webhookConfig) {
        this.apiToken = webhookConfig.api().token();

        if (this.apiToken == null || this.apiToken.isBlank()) {
            log.warn("GITHUB_API_TOKEN not set. Git diff fetch will fail. "
                    + "Set GITHUB_API_TOKEN env var before starting app.");
        }

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);

        WebhookConfig.Api.Proxy proxy = webhookConfig.api().proxy();
        if (proxy != null && proxy.host() != null && !proxy.host().isBlank()
                && proxy.port() != null && !proxy.port().isBlank()) {
            try {
                int port = Integer.parseInt(proxy.port());
                builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), port)));
                log.info("GitHubClient using proxy: {}:{}", proxy.host(), port);
            } catch (NumberFormatException e) {
                log.warn("Invalid proxy port: {}", proxy.port());
            }
        }

        this.httpClient = builder.build();
    }

    public String fetchDiff(String diffUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(diffUrl))
                .header("Accept", "application/vnd.github.v3.diff")
                .header("Authorization", "Bearer " + apiToken)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String msg = response.body() != null && response.body().length() < 500
                    ? response.body() : "(empty or large body)";
            throw new RuntimeException("Failed to fetch diff: HTTP " + response.statusCode()
                    + " - " + msg);
        }

        String body = response.body();

        // Detect HTML response (login page instead of diff)
        if (body != null && (body.trim().startsWith("<!DOCTYPE") || body.trim().startsWith("<html"))) {
            log.warn("Received HTML response instead of diff from {} — possible auth issue", diffUrl);
            return "";
        }

        return body;
    }
}
