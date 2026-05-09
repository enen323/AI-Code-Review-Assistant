package com.ai.code.review.trigger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Deserialization model for GitHub webhook JSON payload.
 * Contains only the fields needed for review task creation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubWebhookPayload(

    @JsonProperty("action") String action,

    @JsonProperty("pull_request") PullRequest pullRequest,

    @JsonProperty("repository") Repository repository,

    @JsonProperty("number") int number
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Deserializes a GitHub webhook payload from its JSON representation.
     *
     * @param json the raw JSON string
     * @return the parsed payload
     * @throws Exception if parsing fails
     */
    public static GitHubWebhookPayload fromJson(String json) throws Exception {
        return OBJECT_MAPPER.readValue(json, GitHubWebhookPayload.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(
        @JsonProperty("title") String title,
        @JsonProperty("body") String body,
        @JsonProperty("head") GitRef head,
        @JsonProperty("base") GitRef base,
        @JsonProperty("diff_url") String diffUrl
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitRef(
        @JsonProperty("ref") String ref,
        @JsonProperty("sha") String sha
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(
        @JsonProperty("full_name") String fullName
    ) {}
}
