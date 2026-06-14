package com.ai.code.review;

import com.ai.code.review.agent.ReviewAgent;
import com.ai.code.review.aggregation.ResultAggregator;
import com.ai.code.review.context.ContextBuilder;
import com.ai.code.review.context.GitHubClient;
import com.ai.code.review.trigger.GitHubWebhookPayload;
import com.ai.code.review.memory.FeedbackRecordMapper;
import com.ai.code.review.memory.MemoryService;
import com.ai.code.review.memory.RuleStatsMapper;
import com.ai.code.review.model.*;
import com.ai.code.review.orchestration.OrchestratorAgent;
import com.ai.code.review.report.PRCommentPublisher;
import com.ai.code.review.report.ReportGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the AI Code Review Assistant.
 *
 * Verifies the full pipeline end-to-end with mocked external dependencies.
 * Tests WebhookController request handling, ContextBuilder diff parsing,
 * OrchestratorAgent multi-agent dispatch, ResultAggregator dedup/sorting,
 * and ReportGenerator markdown generation.
 *
 * Database and external API dependencies are mocked to allow standalone
 * execution without PostgreSQL or network access.
 */
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
        + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
    "spring.ai.openai.api-key=test-key",
    "github.api.token=test-token",
    "github.api.webhook.secret=test-secret"
})
@AutoConfigureMockMvc
class AiCodeReviewIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContextBuilder contextBuilder;

    @Autowired
    private OrchestratorAgent orchestrator;

    @Autowired
    private List<ReviewAgent> agents;

    @Autowired
    private ResultAggregator resultAggregator;

    @Autowired
    private ReportGenerator reportGenerator;

    @MockBean
    private GitHubClient gitHubClient;

    @MockBean
    private ChatClient chatClient;

    @MockBean
    private MemoryService memoryService;

    @MockBean
    private PRCommentPublisher prCommentPublisher;

    @MockBean
    private FeedbackRecordMapper feedbackRecordMapper;

    @MockBean
    private RuleStatsMapper ruleStatsMapper;

    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.PromptSystemSpec systemSpec;
    private ChatClient.PromptUserSpec userSpec;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TEST_SECRET = "test-secret";
    private static final String SAMPLE_DIFF = """
            diff --git a/src/main/java/com/example/App.java b/src/main/java/com/example/App.java
            new file mode 100644
            index 0000000..abc1234
            --- /dev/null
            +++ b/src/main/java/com/example/App.java
            @@ -0,0 +1,20 @@
            +package com.example;
            +
            +import java.sql.Connection;
            +import java.sql.PreparedStatement;
            +import java.sql.ResultSet;
            +
            +public class App {
            +    public String findUser(String id) {
            +        String query = "SELECT * FROM users WHERE id = '" + id + "'";
            +        try {
            +            Connection conn = getConnection();
            +            PreparedStatement stmt = conn.prepareStatement(query);
            +            ResultSet rs = stmt.executeQuery();
            +        } catch (Exception e) {
            +        }
            +        return null;
            +    }
            +}
            """;

    private static final String AGENT_JSON_RESPONSE = """
            {
                "severity": "MAJOR",
                "category": "sql-injection",
                "filePath": "src/main/java/com/example/App.java",
                "lineStart": 8,
                "lineEnd": 8,
                "title": "SQL Injection Risk",
                "description": "User input is directly concatenated into SQL query string, creating a SQL injection vulnerability.",
                "suggestion": "Use parameterized queries with PreparedStatement and avoid string concatenation."
            }
            """;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Setup ChatClient mock chain for all agents
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        systemSpec = mock(ChatClient.PromptSystemSpec.class);
        userSpec = mock(ChatClient.PromptUserSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);

        when(requestSpec.system(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ChatClient.PromptSystemSpec> consumer = invocation.getArgument(0);
            consumer.accept(systemSpec);
            return requestSpec;
        });

        when(requestSpec.user(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ChatClient.PromptUserSpec> consumer = invocation.getArgument(0);
            consumer.accept(userSpec);
            return requestSpec;
        });

        when(requestSpec.call()).thenReturn(callSpec);
        when(systemSpec.text(anyString())).thenReturn(systemSpec);
        when(userSpec.text(anyString())).thenReturn(userSpec);
        when(callSpec.content()).thenReturn(AGENT_JSON_RESPONSE);

        // Setup MemoryService mock — no rules are downgraded by default
        when(memoryService.isRuleDowngraded(anyString(), anyString())).thenReturn(false);
    }

    // -----------------------------------------------------------------------
    // Webhook Endpoint Tests
    // -----------------------------------------------------------------------

    /**
     * Tests that a valid signed webhook request with PR_OPENED action
     * is accepted and returns 200 OK.
     */
    @Test
    void testWebhookEndpoint_ValidRequest_Success() throws Exception {
        String payload = createWebhookPayload("opened");
        String signature = computeHmac(payload, TEST_SECRET);

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + signature)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("Webhook processed successfully"));
    }

    /**
     * Tests that a valid signed webhook request with PR_SYNCHRONIZED action
     * is accepted and returns 200 OK.
     */
    @Test
    void testWebhookEndpoint_PrSynchronize_Success() throws Exception {
        String payload = createWebhookPayload("synchronize");
        String signature = computeHmac(payload, TEST_SECRET);

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + signature)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("Webhook processed successfully"));
    }

    /**
     * Tests that a request with an invalid HMAC-SHA256 signature
     * is rejected with 401 Unauthorized.
     */
    @Test
    void testWebhookEndpoint_InvalidSignature_Returns401() throws Exception {
        String payload = createWebhookPayload("opened");

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=invalidhexsignature")
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Signature mismatch"));
    }

    /**
     * Tests that a request with a missing signature header
     * is rejected with 401 Unauthorized.
     */
    @Test
    void testWebhookEndpoint_MissingSignature_Returns401() throws Exception {
        String payload = createWebhookPayload("opened");

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Signature mismatch"));
    }

    /**
     * Tests that an unsupported action in the webhook payload
     * is rejected with 400 Bad Request.
     */
    @Test
    void testWebhookEndpoint_UnsupportedAction_Returns400() throws Exception {
        String payload = createWebhookPayload("labeled");
        String signature = computeHmac(payload, TEST_SECRET);

        mockMvc.perform(post("/webhook/pr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + signature)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // Full Pipeline Integration Test
    // -----------------------------------------------------------------------

    /**
     * Tests the full review pipeline from context building through
     * orchestration, aggregation, and report generation.
     *
     * Verifies that:
     * - ContextBuilder correctly parses diff content into CodeContext
     * - OrchestratorAgent dispatches all available agents
     * - ResultAggregator deduplicates and sorts results
     * - ReportGenerator produces valid Markdown output
     */
    @Test
    void testFullPipelineFromContextToReport() throws Exception {
        // Arrange
        when(gitHubClient.fetchDiff(anyString())).thenReturn(SAMPLE_DIFF);

        ReviewTask task = new ReviewTask(
                "owner/repo#42",
                "owner/repo",
                42,
                "Test PR",
                "Test description",
                "feature-branch",
                "main",
                "abc123def456",
                "https://api.github.com/repos/owner/repo/pulls/42",
                TriggerEvent.PR_OPENED
        );

        // Act 1: Build CodeContext (ContextBuilder + GitDiffParser)
        CodeContext context = contextBuilder.build(task);

        // Verify CodeContext
        assertNotNull(context, "CodeContext should not be null");
        assertEquals("owner/repo#42", context.prId());
        assertFalse(context.changedFiles().isEmpty(),
                "Changed files should be parsed from diff");
        assertFalse(context.diffBlocks().isEmpty(),
                "Diff blocks should be parsed from diff");

        ChangedFile changedFile = context.changedFiles().getFirst();
        assertEquals("src/main/java/com/example/App.java", changedFile.filePath());
        assertEquals(ChangeType.ADDED, changedFile.changeType());
        assertTrue(changedFile.additions() > 0, "Should have additions");

        // Dependency graph should contain import information
        assertTrue(context.dependencyGraph().contains("import"),
                "Dependency graph should include import data");

        // Act 2: Orchestrate agents (OrchestratorAgent + all ReviewAgents)
        List<ReviewResult> results = orchestrator.orchestrate(context, agents);

        assertNotNull(results, "Results should not be null");
        assertFalse(results.isEmpty(),
                "Orchestration should produce at least one result");
        assertTrue(agents.size() >= 4,
                "Should have at least 4 agents: security, logic, architecture, code-style");

        // Verify agents were called (ChatClient was invoked)
        verify(chatClient, atLeast(agents.size())).prompt();

        // Act 3: Aggregate results (ResultAggregator)
        AggregatedReport aggregatedReport = resultAggregator.aggregate(results, task.prId());

        assertNotNull(aggregatedReport, "Aggregated report should not be null");
        assertEquals("owner/repo#42", aggregatedReport.prId());
        assertFalse(aggregatedReport.results().isEmpty(),
                "Aggregated report should contain results");

        // Verify severity counts are populated
        int totalCount = aggregatedReport.criticalCount()
                + aggregatedReport.majorCount()
                + aggregatedReport.minorCount();
        assertTrue(totalCount > 0,
                "Report should have at least one finding with severity");

        // Act 4: Generate Markdown report (ReportGenerator)
        String markdown = reportGenerator.generateMarkdown(aggregatedReport);

        assertNotNull(markdown, "Markdown report should not be null");
        assertTrue(markdown.contains("AI Code Review Report"),
                "Report should have title header");
        assertTrue(markdown.contains("### Summary"),
                "Report should have Summary section");
        assertTrue(markdown.contains("### Findings by File"),
                "Report should have Findings by File section");
        assertTrue(markdown.contains("### Agent Summary"),
                "Report should have Agent Summary section");
        assertTrue(markdown.contains("src/main/java/com/example/App.java"),
                "Report should contain the reviewed file path");
    }

    /**
     * Tests that the pipeline handles empty diff content gracefully.
     */
    @Test
    void testPipelineWithEmptyDiff() throws Exception {
        when(gitHubClient.fetchDiff(anyString())).thenReturn("");

        ReviewTask task = new ReviewTask(
                "owner/repo#99",
                "owner/repo",
                99,
                "Empty PR",
                "",
                "feature",
                "main",
                "sha",
                "https://api.github.com/repos/owner/repo/pulls/99",
                TriggerEvent.PR_OPENED
        );

        // Build context with empty diff
        CodeContext context = contextBuilder.build(task);
        assertNotNull(context);
        assertTrue(context.changedFiles().isEmpty(),
                "No changed files for empty diff");

        // Orchestrate with empty context
        List<ReviewResult> results = orchestrator.orchestrate(context, agents);
        assertNotNull(results);
        // Agents may still produce "no issues found" results

        // Aggregate
        AggregatedReport report = resultAggregator.aggregate(results, task.prId());
        assertNotNull(report);

        // Generate report
        String markdown = reportGenerator.generateMarkdown(report);
        assertNotNull(markdown);
        assertTrue(markdown.contains("AI Code Review Report"));
    }

    /**
     * Tests that the pipeline handles null context gracefully.
     */
    @Test
    void testPipelineWithNullContext() {
        List<ReviewResult> results = orchestrator.orchestrate(null, agents);
        assertNotNull(results);
        assertTrue(results.isEmpty(),
                "Null context should produce empty results");
    }

    // -----------------------------------------------------------------------
    // Agent Configuration Tests
    // -----------------------------------------------------------------------

    /**
     * Tests that all expected agent types are registered in the context.
     */
    @Test
    void testAllAgentTypesAreRegistered() {
        List<String> agentTypes = agents.stream()
                .map(ReviewAgent::getAgentType)
                .toList();

        assertTrue(agentTypes.contains("security"),
                "SecurityAgent should be registered");
        assertTrue(agentTypes.contains("logic"),
                "LogicAgent should be registered");
        assertTrue(agentTypes.contains("architecture"),
                "ArchitectureAgent should be registered");
        assertTrue(agentTypes.contains("code-style"),
                "CodeStyleAgent should be registered");
    }

    // -----------------------------------------------------------------------
    // Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Creates a sample GitHub webhook JSON payload.
     */
    private String createWebhookPayload(String action) throws Exception {
        return objectMapper.writeValueAsString(
                new GitHubWebhookPayload(
                        action,
                        new GitHubWebhookPayload.PullRequest(
                                "Test PR",
                                "This is a test PR description",
                                new GitHubWebhookPayload.GitRef("feature-branch", "abc123def456"),
                                new GitHubWebhookPayload.GitRef("main", "789012ghi345"),
                                "https://api.github.com/repos/owner/repo/pulls/42"
                        ),
                        new GitHubWebhookPayload.Repository("owner/repo"),
                        42
                )
        );
    }

    /**
     * Computes HMAC-SHA256 hex digest for webhook signature testing.
     */
    private String computeHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
        mac.init(keySpec);
        byte[] hmacBytes = mac.doFinal(payload.getBytes());
        return HexFormat.of().formatHex(hmacBytes);
    }
}
