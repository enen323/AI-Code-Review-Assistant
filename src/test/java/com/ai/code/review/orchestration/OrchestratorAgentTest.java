package com.ai.code.review.orchestration;

import com.ai.code.review.agent.ReviewAgent;
import com.ai.code.review.model.CodeContext;
import com.ai.code.review.model.ReviewResult;
import com.ai.code.review.model.ReviewSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for OrchestratorAgent verifying parallel execution and error handling.
 */
class OrchestratorAgentTest {

    private OrchestratorAgent orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new OrchestratorAgent();
    }

    /**
     * Tests that orchestrate calls all agents and collects their results.
     */
    @Test
    void testOrchestrateCollectsAllAgentResults() {
        ReviewAgent agent1 = mock(ReviewAgent.class);
        ReviewAgent agent2 = mock(ReviewAgent.class);
        CodeContext context = new CodeContext("pr-1", List.of(), List.of(), "", "");

        ReviewResult result1 = new ReviewResult("agent1", ReviewSeverity.MAJOR, "security",
                "file1.java", 10, 20, "Issue 1", "Desc 1", "Fix 1");
        ReviewResult result2 = new ReviewResult("agent2", ReviewSeverity.INFO, "logic",
                "file2.java", 5, 15, "Issue 2", "Desc 2", "Fix 2");

        when(agent1.getAgentType()).thenReturn("agent1");
        when(agent1.review(context)).thenReturn(List.of(result1));
        when(agent2.getAgentType()).thenReturn("agent2");
        when(agent2.review(context)).thenReturn(List.of(result2));

        List<ReviewResult> results = orchestrator.orchestrate(context, List.of(agent1, agent2));

        assertEquals(2, results.size());
        assertTrue(results.contains(result1));
        assertTrue(results.contains(result2));
        verify(agent1).review(context);
        verify(agent2).review(context);
    }

    /**
     * Tests that a failing agent does not prevent other agents from completing.
     */
    @Test
    void testFailingAgentDoesNotBlockOtherAgents() {
        ReviewAgent agent1 = mock(ReviewAgent.class);
        ReviewAgent agent2 = mock(ReviewAgent.class);
        CodeContext context = new CodeContext("pr-2", List.of(), List.of(), "", "");

        ReviewResult result2 = new ReviewResult("agent2", ReviewSeverity.MINOR, "logic",
                "file2.java", 1, 5, "Issue 2", "Desc 2", "Fix 2");

        when(agent1.getAgentType()).thenReturn("agent1");
        when(agent1.review(context)).thenThrow(new RuntimeException("Agent 1 failed"));

        when(agent2.getAgentType()).thenReturn("agent2");
        when(agent2.review(context)).thenReturn(List.of(result2));

        List<ReviewResult> results = orchestrator.orchestrate(context, List.of(agent1, agent2));

        // Only agent2's result should be present
        assertEquals(1, results.size());
        assertEquals(result2, results.getFirst());
        verify(agent1).review(context);
        verify(agent2).review(context);
    }

    /**
     * Tests that all agents failing returns an empty list.
     */
    @Test
    void testAllAgentsFailingReturnsEmptyList() {
        ReviewAgent agent1 = mock(ReviewAgent.class);
        ReviewAgent agent2 = mock(ReviewAgent.class);
        CodeContext context = new CodeContext("pr-3", List.of(), List.of(), "", "");

        when(agent1.getAgentType()).thenReturn("agent1");
        when(agent1.review(context)).thenThrow(new RuntimeException("Agent 1 failed"));
        when(agent2.getAgentType()).thenReturn("agent2");
        when(agent2.review(context)).thenThrow(new RuntimeException("Agent 2 failed"));

        List<ReviewResult> results = orchestrator.orchestrate(context, List.of(agent1, agent2));

        assertTrue(results.isEmpty());
    }

    /**
     * Tests that an agent returning null is skipped.
     */
    @Test
    void testNullResultFromAgentIsSkipped() {
        ReviewAgent agent1 = mock(ReviewAgent.class);
        ReviewAgent agent2 = mock(ReviewAgent.class);
        CodeContext context = new CodeContext("pr-4", List.of(), List.of(), "", "");

        ReviewResult result2 = new ReviewResult("agent2", ReviewSeverity.INFO, "logic",
                "file2.java", 1, 2, "Issue", "Desc", "Fix");

        when(agent1.getAgentType()).thenReturn("agent1");
        when(agent1.review(context)).thenReturn(null);
        when(agent2.getAgentType()).thenReturn("agent2");
        when(agent2.review(context)).thenReturn(List.of(result2));

        List<ReviewResult> results = orchestrator.orchestrate(context, List.of(agent1, agent2));

        assertEquals(1, results.size());
        assertEquals(result2, results.getFirst());
    }

    /**
     * Tests that null context returns empty list.
     */
    @Test
    void testNullContextReturnsEmptyList() {
        ReviewAgent agent = mock(ReviewAgent.class);
        List<ReviewResult> results = orchestrator.orchestrate(null, List.of(agent));

        assertTrue(results.isEmpty());
        verifyNoInteractions(agent);
    }

    /**
     * Tests that empty agent list returns empty list.
     */
    @Test
    void testEmptyAgentListReturnsEmptyList() {
        CodeContext context = new CodeContext("pr-5", List.of(), List.of(), "", "");
        List<ReviewResult> results = orchestrator.orchestrate(context, List.of());

        assertTrue(results.isEmpty());
    }

    /**
     * Tests that null agent list returns empty list.
     */
    @Test
    void testNullAgentListReturnsEmptyList() {
        CodeContext context = new CodeContext("pr-6", List.of(), List.of(), "", "");
        List<ReviewResult> results = orchestrator.orchestrate(context, null);

        assertTrue(results.isEmpty());
    }

    /**
     * Tests that agents are called in parallel (execution completes in reasonable time).
     */
    @Test
    void testParallelExecution() throws Exception {
        ReviewAgent slowAgent1 = mock(ReviewAgent.class);
        ReviewAgent slowAgent2 = mock(ReviewAgent.class);
        CodeContext context = new CodeContext("pr-7", List.of(), List.of(), "", "");

        ReviewResult result1 = new ReviewResult("agent1", ReviewSeverity.MAJOR, "security",
                "file1.java", 1, 2, "Issue 1", "Desc 1", "Fix 1");
        ReviewResult result2 = new ReviewResult("agent2", ReviewSeverity.INFO, "logic",
                "file2.java", 3, 4, "Issue 2", "Desc 2", "Fix 2");

        when(slowAgent1.getAgentType()).thenReturn("agent1");
        when(slowAgent1.review(context)).thenAnswer(invocation -> {
            Thread.sleep(200);
            return List.of(result1);
        });
        when(slowAgent2.getAgentType()).thenReturn("agent2");
        when(slowAgent2.review(context)).thenAnswer(invocation -> {
            Thread.sleep(200);
            return List.of(result2);
        });

        // If executed sequentially, this would take ~400ms. With virtual threads, much less.
        long start = System.currentTimeMillis();
        List<ReviewResult> results = orchestrator.orchestrate(context, List.of(slowAgent1, slowAgent2));
        long duration = System.currentTimeMillis() - start;

        assertEquals(2, results.size());
        // Should complete in less than 300ms if truly parallel (each takes 200ms)
        assertTrue(duration < 300, "Execution took " + duration + "ms, expected <300ms for parallel execution");
    }
}
