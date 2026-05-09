package com.ai.code.review.agent.security;

import com.ai.code.review.model.*;
import com.ai.code.review.tool.SpotBugsTool;
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
 * Tests for SecurityAgent verifying prompt construction, LLM interaction, and SpotBugs integration.
 */
class SecurityAgentTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.PromptSystemSpec systemSpec;
    private ChatClient.PromptUserSpec userSpec;
    private SpotBugsTool spotBugsTool;

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

        spotBugsTool = mock(SpotBugsTool.class);
    }

    /**
     * Tests that the security agent returns a ReviewResult with the correct agent type.
     */
    @Test
    void testAgentTypeIsSecurity() {
        SecurityAgent agent = new SecurityAgent(chatClient, null);
        assertEquals("security", agent.getAgentType());
    }

    /**
     * Tests that the security agent correctly parses the LLM response into a ReviewResult.
     */
    @Test
    void testReviewReturnsParsedResult() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "CRITICAL",
                        "category": "sql-injection",
                        "filePath": "src/main/java/com/example/UserRepository.java",
                        "lineStart": 42,
                        "lineEnd": 45,
                        "title": "SQL Injection Vulnerability",
                        "description": "User input is directly concatenated into SQL query string.",
                        "suggestion": "Use parameterized queries with PreparedStatement."
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        SecurityAgent agent = new SecurityAgent(chatClient, null);
        CodeContext context = createSampleContext("pr-1");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        ReviewResult result = results.getFirst();
        assertEquals("security", result.agentId());
        assertEquals(ReviewSeverity.CRITICAL, result.severity());
        assertEquals("sql-injection", result.category());
        assertEquals("src/main/java/com/example/UserRepository.java", result.filePath());
        assertEquals(42, result.lineStart());
        assertEquals(45, result.lineEnd());
        assertEquals("SQL Injection Vulnerability", result.title());
        assertTrue(result.description().contains("SQL query"));
        assertTrue(result.suggestion().contains("PreparedStatement"));
    }

    /**
     * Tests that the system prompt contains security-related instructions.
     */
    @Test
    void testSystemPromptContainsSecurityContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        SecurityAgent agent = new SecurityAgent(chatClient, null);
        agent.review(createSampleContext("pr-2"));

        // Verify system prompt contains security-focused instructions
        verify(systemSpec).text(argThat((String prompt) ->
                prompt.contains("security") &&
                prompt.contains("SQL Injection") &&
                prompt.contains("XSS") &&
                prompt.contains("Authentication/Authorization")
        ));
    }

    /**
     * Tests that the user prompt includes the code context.
     */
    @Test
    void testUserPromptContainsCodeContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        SecurityAgent agent = new SecurityAgent(chatClient, null);
        CodeContext context = createSampleContext("pr-3");
        agent.review(context);

        // Verify user prompt contains PR ID and code context
        verify(userSpec).text(argThat((String prompt) ->
                prompt.contains("pr-3") &&
                prompt.contains("Changed Files")
        ));
    }

    /**
     * Tests that SpotBugs findings are appended when SpotBugsTool is available.
     */
    @Test
    void testSpotBugsFindingsAreIncluded() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "MAJOR",
                        "category": "xss",
                        "filePath": "src/main/java/com/example/Controller.java",
                        "lineStart": 15,
                        "lineEnd": 18,
                        "title": "XSS Vulnerability",
                        "description": "User input rendered without escaping.",
                        "suggestion": "Use proper output encoding."
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        // Mock SpotBugs to return a finding
        SpotBugsTool.BugPattern bugPattern = new SpotBugsTool.BugPattern(
                "src/main/java/com/example/BadCode.java", 25,
                "EMPTY_CATCH_BLOCK", "Empty catch block detected");
        when(spotBugsTool.analyze(anyString(), anyString()))
                .thenReturn(List.of(bugPattern));

        SecurityAgent agent = new SecurityAgent(chatClient, spotBugsTool);
        CodeContext context = createSampleContext("pr-4");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        // The SpotBugs findings should be included as separate results
        boolean hasSpotBugsFinding = results.stream()
                .anyMatch(r -> r.category().equals("static-analysis") &&
                        r.title().contains("EMPTY_CATCH_BLOCK"));
        assertTrue(hasSpotBugsFinding, "Should include SpotBugs static analysis findings");
    }

    /**
     * Tests that the agent handles SpotBugs failures gracefully.
     */
    @Test
    void testSpotBugsFailureDoesNotFailReview() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "INFO",
                        "category": "info",
                        "filePath": "src/main/java/com/example/Config.java",
                        "lineStart": 1,
                        "lineEnd": 1,
                        "title": "No issues",
                        "description": "No security issues found.",
                        "suggestion": ""
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        // SpotBugs throws an exception
        when(spotBugsTool.analyze(anyString(), anyString()))
                .thenThrow(new RuntimeException("SpotBugs failed"));

        SecurityAgent agent = new SecurityAgent(chatClient, spotBugsTool);
        CodeContext context = createSampleContext("pr-5");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertEquals("security", results.getFirst().agentId());
    }

    /**
     * Tests that LLM failure returns a graceful error result.
     */
    @Test
    void testLlmFailureReturnsGracefulError() {
        when(callSpec.content()).thenThrow(new RuntimeException("LLM API error"));

        SecurityAgent agent = new SecurityAgent(chatClient, null);
        CodeContext context = createSampleContext("pr-6");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        ReviewResult result = results.getFirst();
        assertEquals("security", result.agentId());
        assertEquals(ReviewSeverity.INFO, result.severity());
        assertTrue(result.title().contains("failed") || result.title().contains("Failed"));
    }

    /**
     * Tests that the agent handles empty code context.
     */
    @Test
    void testEmptyCodeContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        SecurityAgent agent = new SecurityAgent(chatClient, null);
        CodeContext emptyContext = new CodeContext("pr-empty", List.of(), List.of(), "", "");
        List<ReviewResult> results = agent.review(emptyContext);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertEquals("security", results.getFirst().agentId());
    }

    /**
     * Creates a sample code context for testing.
     */
    private CodeContext createSampleContext(String prId) {
        ChangedFile changedFile = new ChangedFile(
                "src/main/java/com/example/UserRepository.java",
                ChangeType.MODIFIED, 5, 2, "diff content here");
        DiffBlock diffBlock = new DiffBlock(
                "src/main/java/com/example/UserRepository.java",
                40, 42, 5, 8,
                "@@ -40,5 +42,8 @@",
                List.of(" public User findUser(String id) {",
                        "-    String query = \"SELECT * FROM users WHERE id = '\" + id + \"'\";",
                        "+    String query = \"SELECT * FROM users WHERE id = ?\";",
                        "+    PreparedStatement stmt = connection.prepareStatement(query);",
                        "+    stmt.setString(1, id);",
                        "     return executeQuery(query);",
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
                        "category": "security-review",
                        "filePath": "",
                        "lineStart": 0,
                        "lineEnd": 0,
                        "title": "No security issues found",
                        "description": "No issues detected.",
                        "suggestion": ""
                    }]
                }
                """;
    }
}
