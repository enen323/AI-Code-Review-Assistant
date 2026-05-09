package com.ai.code.review.agent.architecture;

import com.ai.code.review.model.*;
import com.ai.code.review.tool.ArchUnitTool;
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
 * Tests for ArchitectureAgent verifying prompt construction, LLM interaction, and ArchUnit integration.
 */
class ArchitectureAgentTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.PromptSystemSpec systemSpec;
    private ChatClient.PromptUserSpec userSpec;
    private ArchUnitTool archUnitTool;

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

        archUnitTool = mock(ArchUnitTool.class);
    }

    /**
     * Tests that the architecture agent returns the correct agent type.
     */
    @Test
    void testAgentTypeIsArchitecture() {
        ArchitectureAgent agent = new ArchitectureAgent(chatClient, null);
        assertEquals("architecture", agent.getAgentType());
    }

    /**
     * Tests that the architecture agent correctly parses the LLM response into a ReviewResult.
     */
    @Test
    void testReviewReturnsParsedResult() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "CRITICAL",
                        "category": "layer-violation",
                        "filePath": "src/main/java/com/example/controller/UserController.java",
                        "lineStart": 25,
                        "lineEnd": 25,
                        "title": "Layer Violation",
                        "description": "Controller directly calls repository layer.",
                        "suggestion": "Introduce a Service layer between Controller and Repository."
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        ArchitectureAgent agent = new ArchitectureAgent(chatClient, null);
        CodeContext context = createSampleContext("pr-1");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        ReviewResult result = results.getFirst();
        assertEquals("architecture", result.agentId());
        assertEquals(ReviewSeverity.CRITICAL, result.severity());
        assertEquals("layer-violation", result.category());
        assertEquals("src/main/java/com/example/controller/UserController.java", result.filePath());
        assertEquals(25, result.lineStart());
        assertEquals(25, result.lineEnd());
        assertEquals("Layer Violation", result.title());
        assertTrue(result.description().contains("Controller"));
        assertTrue(result.suggestion().contains("Service layer"));
    }

    /**
     * Tests that the system prompt contains architecture-related instructions.
     */
    @Test
    void testSystemPromptContainsArchitectureContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        ArchitectureAgent agent = new ArchitectureAgent(chatClient, null);
        agent.review(createSampleContext("pr-2"));

        verify(systemSpec).text(argThat((String prompt) ->
                prompt.contains("Layering Violations") &&
                prompt.contains("Circular Dependencies") &&
                prompt.contains("Single Responsibility Principle") &&
                prompt.contains("API Design")
        ));
    }

    /**
     * Tests that the user prompt includes the code context and dependency graph.
     */
    @Test
    void testUserPromptContainsCodeContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        ArchitectureAgent agent = new ArchitectureAgent(chatClient, null);
        CodeContext context = createSampleContext("pr-3");
        agent.review(context);

        verify(userSpec).text(argThat((String prompt) ->
                prompt.contains("pr-3") &&
                prompt.contains("Changed Files") &&
                prompt.contains("Dependency Graph")
        ));
    }

    /**
     * Tests that ArchUnit findings are included when ArchUnitTool is available.
     */
    @Test
    void testArchUnitFindingsAreIncluded() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "INFO",
                        "category": "architecture-review",
                        "filePath": "",
                        "lineStart": 0,
                        "lineEnd": 0,
                        "title": "No issues",
                        "description": "No architecture issues found.",
                        "suggestion": ""
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        // Mock ArchUnitTool to return findings for both dependency graph and file content
        ArchUnitTool.ArchIssue archIssue = new ArchUnitTool.ArchIssue(
                "Layer violation detected", "HIGH", "LAYER_VIOLATION");
        when(archUnitTool.analyze(anyString()))
                .thenReturn(List.of(archIssue));

        ArchitectureAgent agent = new ArchitectureAgent(chatClient, archUnitTool);
        CodeContext context = createSampleContext("pr-4");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        boolean hasArchUnitFinding = results.stream()
                .anyMatch(r -> r.category().equals("static-analysis") &&
                        r.title().contains("LAYER_VIOLATION"));
        assertTrue(hasArchUnitFinding, "Should include ArchUnit static analysis findings");
    }

    /**
     * Tests that the agent handles ArchUnit failures gracefully.
     */
    @Test
    void testArchUnitFailureDoesNotFailReview() {
        String jsonResponse = """
                {
                    "results": [{
                        "severity": "INFO",
                        "category": "architecture-review",
                        "filePath": "",
                        "lineStart": 0,
                        "lineEnd": 0,
                        "title": "No issues",
                        "description": "No architecture issues found.",
                        "suggestion": ""
                    }]
                }
                """;
        when(callSpec.content()).thenReturn(jsonResponse);

        // ArchUnit throws an exception
        when(archUnitTool.analyze(anyString()))
                .thenThrow(new RuntimeException("ArchUnit failed"));

        ArchitectureAgent agent = new ArchitectureAgent(chatClient, archUnitTool);
        CodeContext context = createSampleContext("pr-5");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertEquals("architecture", results.getFirst().agentId());
    }

    /**
     * Tests that LLM failure returns a graceful error result.
     */
    @Test
    void testLlmFailureReturnsGracefulError() {
        when(callSpec.content()).thenThrow(new RuntimeException("LLM API error"));

        ArchitectureAgent agent = new ArchitectureAgent(chatClient, null);
        CodeContext context = createSampleContext("pr-6");
        List<ReviewResult> results = agent.review(context);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        ReviewResult result = results.getFirst();
        assertEquals("architecture", result.agentId());
        assertEquals(ReviewSeverity.INFO, result.severity());
        assertTrue(result.title().contains("failed") || result.title().contains("Failed"));
    }

    /**
     * Tests that the agent handles empty code context.
     */
    @Test
    void testEmptyCodeContext() {
        when(callSpec.content()).thenReturn(createEmptyReviewJson());

        ArchitectureAgent agent = new ArchitectureAgent(chatClient, null);
        CodeContext emptyContext = new CodeContext("pr-empty", List.of(), List.of(), "", "");
        List<ReviewResult> results = agent.review(emptyContext);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertEquals("architecture", results.getFirst().agentId());
    }

    /**
     * Creates a sample code context for testing.
     */
    private CodeContext createSampleContext(String prId) {
        ChangedFile changedFile = new ChangedFile(
                "src/main/java/com/example/controller/UserController.java",
                ChangeType.MODIFIED, 3, 1, "@RestController public class UserController { }");
        DiffBlock diffBlock = new DiffBlock(
                "src/main/java/com/example/controller/UserController.java",
                23, 25, 3, 5,
                "@@ -23,3 +25,5 @@",
                List.of(" @RestController",
                        " public class UserController {",
                        "+    @Autowired",
                        "+    private UserRepository userRepository;",
                        " }"));
        return new CodeContext(
                prId,
                List.of(changedFile),
                List.of(diffBlock),
                "com.example.controller -> com.example.repository\ncom.example.service -> com.example.repository\n",
                "Previous review identified similar layer violation patterns.");
    }

    /**
     * Creates a JSON response indicating no issues found.
     */
    private String createEmptyReviewJson() {
        return """
                {
                    "results": [{
                        "severity": "INFO",
                        "category": "architecture-review",
                        "filePath": "",
                        "lineStart": 0,
                        "lineEnd": 0,
                        "title": "No architecture issues found",
                        "description": "No issues detected.",
                        "suggestion": ""
                    }]
                }
                """;
    }
}
