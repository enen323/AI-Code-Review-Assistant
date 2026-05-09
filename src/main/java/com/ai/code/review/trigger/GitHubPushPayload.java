package com.ai.code.review.trigger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Deserialization model for GitHub push event webhook JSON.
 *
 * @see <a href="https://docs.github.com/en/webhooks/webhook-events-and-payloads#push">Push event payload</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPushPayload(

    @JsonProperty("ref") String ref,

    @JsonProperty("before") String before,

    @JsonProperty("after") String after,

    @JsonProperty("repository") Repository repository,

    @JsonProperty("pusher") Pusher pusher,

    @JsonProperty("head_commit") Commit headCommit,

    @JsonProperty("commits") java.util.List<Commit> commits
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static GitHubPushPayload fromJson(String json) throws Exception {
        return OBJECT_MAPPER.readValue(json, GitHubPushPayload.class);
    }

    /** Extract branch name from ref (e.g., "refs/heads/main" -> "main"). */
    public String branchName() {
        if (ref == null) return "";
        if (ref.startsWith("refs/heads/")) return ref.substring("refs/heads/".length());
        if (ref.startsWith("refs/tags/")) return ref.substring("refs/tags/".length());
        return ref;
    }

    /** Build GitHub compare API URL for fetching diff. */
    public String compareDiffUrl() {
        if (repository == null || repository.fullName() == null) return "";
        // New branch push — before is all zeros, can't compare
        if (before != null && before.matches("0+")) return "";
        return "https://api.github.com/repos/" + repository.fullName()
            + "/compare/" + before + "..." + after;
    }

    /** Check if this is a new branch push (before SHA is all zeros). */
    public boolean isNewBranch() {
        return before != null && before.matches("0+");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(
        @JsonProperty("full_name") String fullName
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pusher(
        @JsonProperty("name") String name,
        @JsonProperty("email") String email
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(
        @JsonProperty("id") String id,
        @JsonProperty("message") String message,
        @JsonProperty("url") String url
    ) {}
}
