package com.ai.code.review.orchestration;

import com.ai.code.review.agent.ReviewAgent;
import com.ai.code.review.model.CodeContext;
import com.ai.code.review.model.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Central orchestrator for multi-agent code review.
 *
 * Receives a CodeContext and a list of ReviewAgents, dispatches each agent
 * in parallel using Java 21 virtual threads, collects results, and handles
 * individual agent failures gracefully.
 */
@Service
public class OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);

    /**
     * Orchestrates a multi-agent review by dispatching each agent in parallel.
     *
     * @param context the code context to review
     * @param agents  the list of review agents to invoke
     * @return flat list of ReviewResults from all agents (excluding failed ones)
     */
    public List<ReviewResult> orchestrate(CodeContext context, List<ReviewAgent> agents) {
        if (context == null) {
            log.warn("CodeContext is null, returning empty results");
            return List.of();
        }
        if (agents == null || agents.isEmpty()) {
            log.warn("No agents provided for orchestration");
            return List.of();
        }

        List<ReviewResult> results = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = agents.stream()
                    .map(agent -> executor.submit(() -> {
                        String agentType = agent.getAgentType();
                        log.debug("Starting agent: {}", agentType);
                        try {
                            List<ReviewResult> agentResults = agent.review(context);
                            if (agentResults != null && !agentResults.isEmpty()) {
                                results.addAll(agentResults);
                                log.debug("Agent {} completed with {} results", agentType, agentResults.size());
                            } else {
                                log.warn("Agent {} returned no results", agentType);
                            }
                        } catch (Exception e) {
                            log.error("Agent {} failed during review: {}", agentType, e.getMessage(), e);
                        }
                    }))
                    .toList();

            // Wait for all agents to complete
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    log.warn("Agent task interrupted or failed: {}", e.getMessage());
                }
            }
        }

        log.info("Orchestration complete: {} agents produced {} results", agents.size(), results.size());
        return List.copyOf(results);
    }
}
