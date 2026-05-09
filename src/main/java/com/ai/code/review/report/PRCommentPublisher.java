package com.ai.code.review.report;

import com.ai.code.review.config.WebhookConfig;
import com.ai.code.review.model.AggregatedReport;
import com.ai.code.review.model.ReviewResult;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRateLimit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.HttpConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;

@Component
public class PRCommentPublisher {

    private static final Logger log = LoggerFactory.getLogger(PRCommentPublisher.class);

    private final WebhookConfig.Api.Proxy proxy;

    public PRCommentPublisher(WebhookConfig webhookConfig) {
        this.proxy = webhookConfig.api().proxy();
    }

    public void publishReview(String repoName, int prNumber, AggregatedReport report,
                              String gitHubToken) {
        if (gitHubToken == null || gitHubToken.isBlank()) {
            log.warn("GitHub token not configured; skipping PR comment posting for {}/{}", repoName, prNumber);
            return;
        }
        if (report == null) {
            log.warn("Report is null; skipping PR comment posting for {}/{}", repoName, prNumber);
            return;
        }

        try {
            GitHub github = buildGitHub(gitHubToken);

            checkRateLimit(github);

            GHRepository repository = github.getRepository(repoName);
            GHPullRequest pullRequest = repository.getPullRequest(prNumber);

            String commitSha = getHeadCommitSha(pullRequest);

            String markdown = generateSummaryReport(report);
            pullRequest.comment(markdown);
            log.info("Posted summary comment to PR {}/{}", repoName, prNumber);

            postLineComments(pullRequest, report, commitSha);

            log.info("Review publishing complete for PR {}/{}", repoName, prNumber);

        } catch (GHException e) {
            log.error("GitHub API error while publishing review for {}/{}: {}",
                    repoName, prNumber, e.getMessage(), e);
        } catch (IOException e) {
            log.error("IO error while publishing review for {}/{}: {}",
                    repoName, prNumber, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error while publishing review for {}/{}: {}",
                    repoName, prNumber, e.getMessage(), e);
        }
    }

    private GitHub buildGitHub(String token) throws IOException {
        GitHubBuilder builder = new GitHubBuilder().withOAuthToken(token);

        if (proxy != null && proxy.host() != null && !proxy.host().isBlank()
                && proxy.port() != null && !proxy.port().isBlank()) {
            try {
                int port = Integer.parseInt(proxy.port());
                builder.withConnector((HttpConnector) url -> {
                    java.net.Proxy p = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                            new java.net.InetSocketAddress(proxy.host(), port));
                    return (HttpURLConnection) url.openConnection(p);
                });
                log.info("PRCommentPublisher using proxy: {}:{}", proxy.host(), port);
            } catch (NumberFormatException e) {
                log.warn("Invalid proxy port: {}", proxy.port());
            }
        }

        return builder.build();
    }

    private void checkRateLimit(GitHub github) throws IOException {
        GHRateLimit rateLimit = github.getRateLimit();
        if (rateLimit != null) {
            GHRateLimit.Record core = rateLimit.getCore();
            int remaining = core.getRemaining();
            int limit = core.getLimit();
            log.debug("GitHub API rate limit: {} remaining out of {}",
                    remaining, limit);
            if (remaining < 10) {
                log.warn("GitHub API rate limit nearly exhausted: only {} calls remaining", remaining);
            }
        }
    }

    private String getHeadCommitSha(GHPullRequest pullRequest) {
        try {
            GHCommitPointer head = pullRequest.getHead();
            if (head != null && head.getSha() != null) {
                return head.getSha();
            }
        } catch (Exception e) {
            log.warn("Could not fetch head commit SHA for PR {}: {}",
                    pullRequest.getNumber(), e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void postLineComments(GHPullRequest pullRequest, AggregatedReport report,
                                  String commitSha) {
        if (commitSha == null) {
            log.warn("No commit SHA available; skipping line-specific comments");
            return;
        }

        List<ReviewResult> lineResults = report.results().stream()
                .filter(r -> r.filePath() != null && !r.filePath().isBlank() && r.lineStart() > 0)
                .toList();

        if (lineResults.isEmpty()) {
            log.debug("No line-specific findings to post");
            return;
        }

        for (ReviewResult result : lineResults) {
            try {
                String commentBody = formatLineComment(result);
                pullRequest.createReviewComment(commentBody, commitSha,
                        result.filePath(), result.lineStart());
                log.debug("Posted line comment on {}:{}", result.filePath(), result.lineStart());
            } catch (IOException e) {
                String msg = e.getMessage();
                // 422/position errors mean GitHub can't resolve diff position.
                // Fall back to general file comment instead of failing silently.
                if (msg != null && msg.contains("position") && msg.contains("could not be resolved")) {
                    log.warn("Line position invalid for {}:{} on PR {} — diff may have changed. " +
                            "Posting as file comment instead.",
                            result.filePath(), result.lineStart(), pullRequest.getNumber());
                    postGeneralFileComment(pullRequest, result, commitSha);
                } else {
                    log.warn("Failed to post line comment on {}:{} for PR {}: {}",
                            result.filePath(), result.lineStart(),
                            pullRequest.getNumber(), msg);
                }
            }
        }
    }

    private String formatLineComment(ReviewResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(result.severity()).append("**");
        if (result.category() != null) {
            sb.append(" [").append(result.category()).append("]");
        }
        sb.append(": ").append(result.title()).append("\n\n");
        if (result.description() != null && !result.description().isBlank()) {
            sb.append(result.description()).append("\n\n");
        }
        if (result.suggestion() != null && !result.suggestion().isBlank()) {
            sb.append("**Suggestion:** ").append(result.suggestion());
        }
        return sb.toString();
    }

    /**
     * Post a general PR comment mentioning file and line, as fallback when
     * line-specific comment fails (e.g., diff position can't be resolved).
     */
    private void postGeneralFileComment(GHPullRequest pullRequest, ReviewResult result,
                                         String commitSha) {
        try {
            String body = "**" + result.severity() + "**"
                    + (result.category() != null ? " [" + result.category() + "]" : "")
                    + ": " + result.title() + "\n\n"
                    + "File: `" + result.filePath() + ":" + result.lineStart() + "`\n\n"
                    + (result.description() != null ? result.description() : "");
            pullRequest.comment(body);
            log.debug("Posted fallback comment for {}:{}", result.filePath(), result.lineStart());
        } catch (IOException e) {
            log.warn("Fallback comment also failed for {}:{}: {}",
                    result.filePath(), result.lineStart(), e.getMessage());
        }
    }

    private String generateSummaryReport(AggregatedReport report) {
        ReportGenerator generator = new ReportGenerator();
        return generator.generateMarkdown(report);
    }
}
