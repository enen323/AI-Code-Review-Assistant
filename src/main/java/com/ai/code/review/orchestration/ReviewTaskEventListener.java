package com.ai.code.review.orchestration;

import com.ai.code.review.agent.ReviewAgent;
import com.ai.code.review.aggregation.ResultAggregator;
import com.ai.code.review.context.ContextBuilder;
import com.ai.code.review.memory.FalsePositiveFilter;
import com.ai.code.review.model.AggregatedReport;
import com.ai.code.review.model.CodeContext;
import com.ai.code.review.model.ReviewResult;
import com.ai.code.review.model.ReviewTask;
import com.ai.code.review.report.PRCommentPublisher;
import com.ai.code.review.report.ReportGenerator;
import com.ai.code.review.model.TriggerEvent;
import com.ai.code.review.trigger.ReviewTaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ReviewTaskEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewTaskEventListener.class);

    private final ContextBuilder contextBuilder;
    private final OrchestratorAgent orchestrator;
    private final List<ReviewAgent> agents;
    private final ResultAggregator resultAggregator;
    private final ReportGenerator reportGenerator;
    private final PRCommentPublisher prCommentPublisher;
    private final FalsePositiveFilter falsePositiveFilter;
    private final String gitHubToken;

    public ReviewTaskEventListener(ContextBuilder contextBuilder,
                                   OrchestratorAgent orchestrator,
                                   List<ReviewAgent> agents,
                                   ResultAggregator resultAggregator,
                                   ReportGenerator reportGenerator,
                                   PRCommentPublisher prCommentPublisher,
                                   FalsePositiveFilter falsePositiveFilter,
                                   @Value("${github.api.token}") String gitHubToken) {
        this.contextBuilder = contextBuilder;
        this.orchestrator = orchestrator;
        this.agents = agents;
        this.resultAggregator = resultAggregator;
        this.reportGenerator = reportGenerator;
        this.prCommentPublisher = prCommentPublisher;
        this.falsePositiveFilter = falsePositiveFilter;
        this.gitHubToken = gitHubToken;
    }

    @EventListener
    @Async
    public void handleReviewTaskEvent(ReviewTaskEvent event) {
        ReviewTask task = event.getReviewTask();
        if (task == null) {
            log.warn("Received ReviewTaskEvent with null task");
            return;
        }

        log.info("Processing ReviewTaskEvent for {} (repo: {}, trigger: {})",
                task.prId(), task.repoName(), task.triggerEvent());        try {
            CodeContext context = contextBuilder.build(task);

            List<ReviewResult> results = orchestrator.orchestrate(context, agents);

            log.info("Review complete for {}. {} agents produced {} results.",
                    task.prId(), agents.size(), results.size());

            for (ReviewResult result : results) {
                log.info("  [{}][{}] {} - {}:{}: {}",
                        result.severity(),
                        result.category(),
                        result.filePath(),
                        result.lineStart(),
                        result.lineEnd(),
                        result.title());
            }

            int preFilterCount = results.size();
            results = falsePositiveFilter.filter(results);
            log.info("False-positive filtering: {} results reduced to {} for PR {}",
                    preFilterCount, results.size(), task.prId());

            AggregatedReport aggregatedReport = resultAggregator.aggregate(results, task.prId());

            String markdownReport = reportGenerator.generateMarkdown(aggregatedReport);

            log.info("Generated report for {}:\n{}", task.prId(), markdownReport);

            if (task.triggerEvent() == TriggerEvent.PUSH) {
                savePushReport(task, markdownReport);
            } else if (gitHubToken != null && !gitHubToken.isBlank()) {
                prCommentPublisher.publishReview(
                        task.repoName(),
                        task.prNumber(),
                        aggregatedReport,
                        gitHubToken
                );
            } else {
                log.info("GitHub token not configured; report will not be posted to PR {}/{}",
                        task.repoName(), task.prNumber());
            }

        } catch (Exception e) {
            log.error("Review orchestration failed for {}: {}", task.prId(), e.getMessage(), e);
        }
    }

    /**
     * Save push-triggered review report to local file system.
     */
    private void savePushReport(ReviewTask task, String markdownReport) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String safeName = task.repoName().replace("/", "_");
            String filename = "review-" + safeName + "-" + timestamp + ".md";
            Path reportsDir = Paths.get("reports");
            Files.createDirectories(reportsDir);
            Path reportFile = reportsDir.resolve(filename);
            Files.writeString(reportFile, markdownReport);
            log.info("Push review report saved to: {}", reportFile.toAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to save push review report for {}: {}", task.prId(), e.getMessage(), e);
        }
    }
}
