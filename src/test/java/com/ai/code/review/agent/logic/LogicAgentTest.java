package com.ai.code.review.agent.logic;

import com.ai.code.review.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Tests for LogicAgent verifying prompt construction, LLM interaction, and result parsing.
 */
class LogicAgentTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.PromptSystemSpec systemSpec;
    private ChatClient.PromptUserSpec userSpec;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        systemSpec = mock(ChatClient.PromptSystemSpec.class);
        userSpec = mock(ChatClient.PromptUserSpec.class);

        // Chain: prompt() -> ChatClientRequestSpec
        when(chatClient.prompt()).thenReturn(requestSpec);

        // Chain: system(consumer) executes consumer then returns requestSpec
        when(requestSpec.system(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ChatClient.PromptSystemSpec> consumer = invocation.getArgument(0);
            consumer.accept(systemSpec);
            return requestSpec;
        });

        // Chain: user(consumer) executes consumer then returns requestSpec
        when(requestSpec.user(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ChatClient.PromptUserSpec> consumer = invocation.getArgument(0);
            consumer.accept(userSpec);
            return requestSpec;
        });

        // Chain: call() -> CallResponseSpec, content() -> String
        when(requestSpec.call()).thenReturn(callSpec);

        // Stub PromptSystemSpec.text() to return itself
        when(systemSpec.text(anyString())).thenReturn(systemSpec);
        // Stub PromptUserSpec.text() to return itself
        when(userSpec.text(anyString())).thenReturn(userSpec);
    }

    /**
     * Tests that the logic agent returns a ReviewResult with the correct agent type.
     */
    @Test
    void testAgentTypeIsLogic() {
        LogicAgent agent = new LogicAgent(chatClient);
        assertEquals("logic", agent.getAgentType());
    }

    /**
     * Tests that the logic agent correctly parses the LLM response into a ReviewResult.
     */
    @Test
    void testReviewReturnsParsedResult() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "MAJOR",
                        "category": "null-pointer",
                        "filePath": "src/main/java/com/example/OrderService.java",
                        "lineStart": 25,
                        "lineEnd": 25,
                        "title": "Null Pointer Risk",
                        "description": "getUser() may return null but result is dereferenced without null check.",
                        "suggestion": "Add null check before dereferencing user object."
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        LogicAgent agent = new LogicAgent(chatClient);
        CodeContext context = createSampleContext("pr-1");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        ReviewResult result = results.getFirst();
        assertEquals("logic", result.agentId());
        assertEquals(ReviewSeverity.MAJOR, result.severity());
        assertEquals("null-pointer", result.category());
        assertEquals("src/main/java/com/example/OrderService.java", result.filePath());
        assertEquals(25, result.lineStart());
        assertEquals(25, result.lineEnd());
        assertEquals("Null Pointer Risk", result.title());
        assertTrue(result.description().contains("null check"));
        assertTrue(result.suggestion().contains("null check"));
    }

    /**
     * Tests that the system prompt contains logic/bug-related instructions.
     */
    @Test
    void testSystemPromptContainsBugDetectionContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        LogicAgent agent = new LogicAgent(chatClient);
        agent.review(createSampleContext("pr-2"));

        verify(systemSpec).text(argThat((String prompt) ->
                prompt.contains("bug") &&
                prompt.contains("Null Pointer") &&
                prompt.contains("Off-by-One") &&
                prompt.contains("Race Conditions")
        ));
    }

    /**
     * Tests that the user prompt includes the code context.
     */
    @Test
    void testUserPromptContainsCodeContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        LogicAgent agent = new LogicAgent(chatClient);
        CodeContext context = createSampleContext("pr-3");
        agent.review(context);

        verify(userSpec).text(argThat((String prompt) ->
                prompt.contains("pr-3") &&
                prompt.contains("Changed Files")
        ));
    }

    /**
     * Tests that LLM failure returns a graceful error result.
     */
    @Test
    void testLlmFailureReturnsGracefulError() {
        when(callSpec.content()).thenThrow(new RuntimeException("LLM API error"));

        LogicAgent agent = new LogicAgent(chatClient);
        CodeContext context = createSampleContext("pr-4");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        ReviewResult result = results.getFirst();
        assertEquals("logic", result.agentId());
        assertEquals(ReviewSeverity.INFO, result.severity());
        assertTrue(result.title().contains("failed") || result.title().contains("Failed"));
    }

    /**
     * Tests that the agent handles empty code context.
     */
    @Test
    void testEmptyCodeContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        LogicAgent agent = new LogicAgent(chatClient);
        CodeContext emptyContext = new CodeContext("pr-empty", List.of(), List.of(), "", "");
        List<ReviewResult> results = agent.review(emptyContext);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertEquals("logic", results.getFirst().agentId());
    }

    /**
     * Tests MINOR severity result parsing.
     */
    @Test
    void testMinorSeverityResult() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "MINOR",
                        "category": "off-by-one",
                        "filePath": "src/main/java/com/example/OrderService.java",
                        "lineStart": 12,
                        "lineEnd": 14,
                        "title": "Off-by-One Error",
                        "description": "Loop condition uses <= instead of < causing array index out of bounds.",
                        "suggestion": "Change <= to < in loop condition."
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        LogicAgent agent = new LogicAgent(chatClient);
        List<ReviewResult> results = agent.review(createSampleContext("pr-5"));

        assertNotNull(results);
        assertFalse(results.isEmpty());
        ReviewResult result = results.getFirst();
        assertEquals(ReviewSeverity.MINOR, result.severity());
        assertEquals("off-by-one", result.category());
    }

    /**
     * Tests CRITICAL severity result parsing.
     */
    @Test
    void testCriticalSeverityResult() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "CRITICAL",
                        "category": "race-condition",
                        "filePath": "src/main/java/com/example/OrderService.java",
                        "lineStart": 5,
                        "lineEnd": 10,
                        "title": "Race Condition",
                        "description": "Shared counter incremented without synchronization.",
                        "suggestion": "Use AtomicInteger or synchronized block."
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        LogicAgent agent = new LogicAgent(chatClient);
        List<ReviewResult> results = agent.review(createSampleContext("pr-6"));

        assertNotNull(results);
        assertFalse(results.isEmpty());
        ReviewResult result = results.getFirst();
        assertEquals(ReviewSeverity.CRITICAL, result.severity());
        assertEquals("race-condition", result.category());
    }

    /**
     * Creates a sample code context for testing.
     */
    private CodeContext createSampleContext(String prId) {
        ChangedFile changedFile = new ChangedFile(
                "src/main/java/com/example/OrderService.java",
                ChangeType.MODIFIED, 3, 1, "diff content here");
        DiffBlock diffBlock = new DiffBlock(
                "src/main/java/com/example/OrderService.java",
                23, 25, 3, 5,
                "@@ -23,3 +25,5 @@",
                List.of(" public void processOrder(Order order) {",
                        "   User user = getUser(order.getUserId());",
                        "+  user.sendNotification();",
                        " }"));
        return new CodeContext(
                prId,
                List.of(changedFile),
                List.of(diffBlock),
                "File extensions: .java\nDependencies:\n  OrderService.java imports: com.example.User\n",
                "");
    }

    /**
     * Creates a JSON response indicating no issues found.
     */
    private String createEmptyReviewJson() {
        return """
                {
                    "results": [{
                        "severity": "INFO",
                        "category": "logic-review",
                        "filePath": "",
                        "lineStart": 0,
                        "lineEnd": 0,
                        "title": "No logic issues found",
                        "description": "No issues detected.",
                        "suggestion": ""
                    }]
                }
                """;
    }
}
