package com.ai.code.review.agent.codestyle;

import com.ai.code.review.model.*;
import com.ai.code.review.tool.CheckstyleTool;
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
 * Tests for CodeStyleAgent verifying prompt construction, LLM interaction, and Checkstyle integration.
 */
class CodeStyleAgentTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.PromptSystemSpec systemSpec;
    private ChatClient.PromptUserSpec userSpec;
    private CheckstyleTool checkstyleTool;

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

        checkstyleTool = mock(CheckstyleTool.class);
    }

    /**
     * Tests that the code style agent returns the correct agent type.
     */
    @Test
    void testAgentTypeIsCodeStyle() {
        CodeStyleAgent agent = new CodeStyleAgent(chatClient, null);
        assertEquals("code-style", agent.getAgentType());
    }

    /**
     * Tests that the code style agent correctly parses the LLM response into a ReviewResult.
     */
    @Test
    void testReviewReturnsParsedResult() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "MAJOR",
                        "category": "naming-convention",
                        "filePath": "src/main/java/com/example/MyClass.java",
                        "lineStart": 15,
                        "lineEnd": 15,
                        "title": "Invalid Naming Convention",
                        "description": "Variable 'my_variable' uses snake_case instead of camelCase.",
                        "suggestion": "Rename 'my_variable' to 'myVariable'."
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        CodeStyleAgent agent = new CodeStyleAgent(chatClient, null);
        CodeContext context = createSampleContext("pr-1");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        ReviewResult result = results.getFirst();
        assertEquals("code-style", result.agentId());
        assertEquals(ReviewSeverity.MAJOR, result.severity());
        assertEquals("naming-convention", result.category());
        assertEquals("src/main/java/com/example/MyClass.java", result.filePath());
        assertEquals(15, result.lineStart());
        assertEquals(15, result.lineEnd());
        assertEquals("Invalid Naming Convention", result.title());
        assertTrue(result.description().contains("snake_case"));
        assertTrue(result.suggestion().contains("myVariable"));
    }

    /**
     * Tests that the system prompt contains code style-related instructions.
     */
    @Test
    void testSystemPromptContainsCodeStyleContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        CodeStyleAgent agent = new CodeStyleAgent(chatClient, null);
        agent.review(createSampleContext("pr-2"));

        verify(systemSpec).text(argThat((String prompt) ->
                prompt.contains("Naming Conventions") &&
                prompt.contains("camelCase") &&
                prompt.contains("Dead Code") &&
                prompt.contains("Java Best Practices")
        ));
    }

    /**
     * Tests that the user prompt includes the code context.
     */
    @Test
    void testUserPromptContainsCodeContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        CodeStyleAgent agent = new CodeStyleAgent(chatClient, null);
        CodeContext context = createSampleContext("pr-3");
        agent.review(context);

        verify(userSpec).text(argThat((String prompt) ->
                prompt.contains("pr-3") &&
                prompt.contains("Changed Files")
        ));
    }

    /**
     * Tests that Checkstyle findings are included when CheckstyleTool is available.
     */
    @Test
    void testCheckstyleFindingsAreIncluded() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "INFO",
                        "category": "code-style-review",
                        "filePath": "src/main/java/com/example/MyClass.java",
                        "lineStart": 1,
                        "lineEnd": 1,
                        "title": "No issues",
                        "description": "No code style issues found.",
                        "suggestion": ""
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        // Mock CheckstyleTool to return a finding
        CheckstyleTool.CodeStyleIssue issue = new CheckstyleTool.CodeStyleIssue(
                "src/main/java/com/example/MyClass.java", 10,
                "MagicNumber", "Magic number 100 detected");
        when(checkstyleTool.analyze(anyString(), anyString()))
                .thenReturn(List.of(issue));

        CodeStyleAgent agent = new CodeStyleAgent(chatClient, checkstyleTool);
        CodeContext context = createSampleContext("pr-4");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        boolean hasCheckstyleFinding = results.stream()
                .anyMatch(r -> r.category().equals("static-analysis") &&
                        r.title().contains("MagicNumber"));
        assertTrue(hasCheckstyleFinding, "Should include Checkstyle static analysis findings");
    }

    /**
     * Tests that the agent handles Checkstyle failures gracefully.
     */
    @Test
    void testCheckstyleFailureDoesNotFailReview() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "INFO",
                        "category": "code-style-review",
                        "filePath": "",
                        "lineStart": 0,
                        "lineEnd": 0,
                        "title": "No issues",
                        "description": "No code style issues found.",
                        "suggestion": ""
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        // Checkstyle throws an exception
        when(checkstyleTool.analyze(anyString(), anyString()))
                .thenThrow(new RuntimeException("Checkstyle failed"));

        CodeStyleAgent agent = new CodeStyleAgent(chatClient, checkstyleTool);
        CodeContext context = createSampleContext("pr-5");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertEquals("code-style", results.getFirst().agentId());
    }

    /**
     * Tests that LLM failure returns a graceful error result.
     */
    @Test
    void testLlmFailureReturnsGracefulError() {
        when(callSpec.content()).thenThrow(new RuntimeException("LLM API error"));

        CodeStyleAgent agent = new CodeStyleAgent(chatClient, null);
        CodeContext context = createSampleContext("pr-6");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        ReviewResult result = results.getFirst();
        assertEquals("code-style", result.agentId());
        assertEquals(ReviewSeverity.INFO, result.severity());
        assertTrue(result.title().contains("failed") || result.title().contains("Failed"));
    }

    /**
     * Tests that the agent handles empty code context.
     */
    @Test
    void testEmptyCodeContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        CodeStyleAgent agent = new CodeStyleAgent(chatClient, null);
        CodeContext emptyContext = new CodeContext("pr-empty", List.of(), List.of(), "", "");
        List<ReviewResult> results = agent.review(emptyContext);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertEquals("code-style", results.getFirst().agentId());
    }

    /**
     * Creates a sample code context for testing.
     */
    private CodeContext createSampleContext(String prId) {
        ChangedFile changedFile = new ChangedFile(
                "src/main/java/com/example/MyClass.java",
                ChangeType.MODIFIED, 5, 2, "public void process() { int x = 100; }");
        DiffBlock diffBlock = new DiffBlock(
                "src/main/java/com/example/MyClass.java",
                10, 12, 5, 8,
                "@@ -10,5 +12,8 @@",
                List.of(" public void process() {",
                        "-    int x = 100;",
                        "+    int x = calculateValue();",
                        "     System.out.println(x);",
                        " }"));
        return new CodeContext(
                prId,
                List.of(changedFile),
                List.of(diffBlock),
                "File extensions: .java\n",
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
                        "category": "code-style-review",
                        "filePath": "",
                        "lineStart": 0,
                        "lineEnd": 0,
                        "title": "No code style issues found",
                        "description": "No issues detected.",
                        "suggestion": ""
                    }]
                }
                """;
    }
}
