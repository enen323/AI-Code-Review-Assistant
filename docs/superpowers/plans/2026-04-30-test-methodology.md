# AI Code Review Assistant — Test Methodology

> **Purpose:** Guide testing of multi-agent AI code review system triggered by GitHub webhooks. Covers unit, integration, and manual testing.

**System Overview:** Spring Boot 3.4 / Java 21 app. GitHub webhook → diff fetch → parallel AI agents (Security/Logic/CodeStyle/Architecture) + static analysis → aggregation → false-positive filtering → Markdown report → PR comment post.

**Test Stack:** JUnit 5, Mockito, Spring Boot Test, MockMvc, Spring AI Test

---
## 1. Test Levels

### 1.1 Unit Tests (145+ existing)

Run: `mvn test`

| Layer | Component | Approach | Key Mock |
|-------|-----------|----------|----------|
| Trigger | `WebhookController` | MockMvc standalone | `ApplicationEventPublisher` |
| Trigger | `WebhookSignatureValidator` | Pure JUnit, no Spring | None |
| Model | All records/enums | Pure JUnit | None |
| Context | `GitDiffParser` | Pure JUnit, fixture diffs | None |
| Agent | `SecurityAgent` | Mockito + MockMvc | `ChatClient` chain, `SpotBugsTool` |
| Agent | `LogicAgent` | Mockito | `ChatClient` chain |
| Agent | `CodeStyleAgent` | Mockito | `ChatClient` chain, `CheckstyleTool` |
| Agent | `ArchitectureAgent` | Mockito | `ChatClient` chain, `ArchUnitTool` |
| Orchestration | `OrchestratorAgent` | Mockito | Mock agents |
| Tool | `SpotBugsTool` | Pure JUnit | None |
| Tool | `CheckstyleTool` | Pure JUnit | None |
| Tool | `ArchUnitTool` | Pure JUnit | None |
| Memory | `FeedbackCollector` | Mockito | `MemoryService` |
| Memory | `FalsePositiveFilter` | Mockito | `MemoryService` |
| Memory | `MemoryService` | Mockito `@ExtendWith` | `JdbcTemplate` |
| Aggregation | `ResultAggregator` | Pure JUnit | None |
| Report | `ReportGenerator` | Pure JUnit | None |
| Report | `PRCommentPublisher` | Pure JUnit | None |

### 1.2 Integration Tests

Run: `mvn test` (requires DB for some contexts)

| Test Class | Scope | Deps |
|------------|-------|------|
| `AiCodeReviewIntegrationTest` | Full pipeline: webhook → context → orchestration → aggregation → report | `@MockBean` for `GitHubClient`, `ChatClient`, `MemoryService`, `PRCommentPublisher` |
| `AiCodeReviewAssistantApplicationTests` | Context load (disabled, needs DB) | PostgreSQL |

### 1.3 Manual / E2E Tests (no automation)

| Scenario | Setup | Verification |
|----------|-------|-------------|
| GitHub webhook POST | Running app + ngrok/tunnel | 200 response, PR comment appears |
| Signature validation | Real webhook secret | 401 on bad signature |
| GitHub App auth | Valid APP_ID + PRIVATE_KEY | PR comment posted |
| False-positive feedback loop | `/feedback dismiss` comment on PR | Next review skips that rule |
| All agents review real PR | Real PR with code changes | Report with findings |
| Docker deployment | `docker compose up` | App health check passes |

---
## 2. Environment Setup

### 2.1 Prerequisites

```bash
# Required
JDK 21
Docker Desktop
Maven 3.9+

# Environment variables (for integration/e2e)
export AI_API_KEY=sk-xxx
export APP_ID=123456
export PRIVATE_KEY="-----BEGIN RSA PRIVATE KEY-----..."
export WEBHOOK_SECRET=xxx
```

### 2.2 Database

```bash
# Start PostgreSQL with pgvector
docker compose up -d postgres

# Or use local PostgreSQL with:
# createdb ai_code_review
```

### 2.3 Profiles

| Profile | Config | Use Case |
|---------|--------|----------|
| default | PostgreSQL remote, ddl-auto: validate | Production-like |
| local | localhost PG, ddl-auto: update, Flyway off | Dev testing |
| ci | Reduced logging, ddl-auto: none | CI pipeline |

Activate: `-Dspring.profiles.active=local`

---
## 3. Running Tests

### 3.1 All tests

```bash
mvn clean test
```

Note: 3 test files have pre-existing failures (see §7). All run but some fail.

### 3.2 Single test class

```bash
mvn test -Dtest=WebhookControllerTest
mvn test -Dtest=SecurityAgentTest
```

### 3.3 Single test method

```bash
mvn test -Dtest=WebhookControllerTest#testWebhookEndpoint_ValidRequest_Success
```

### 3.4 With profile

```bash
mvn test -Dspring.profiles.active=ci
mvn verify -Dspring.profiles.active=ci  # includes integration tests
```

### 3.5 Skip tests

```bash
mvn package -DskipTests
```

---
## 4. Testing by Feature

### 4.1 Webhook Reception

**What to test:**
- POST `/webhook/pr` with valid payload + signature → 200
- POST with invalid signature → 401
- POST with missing signature → 401
- POST with unsupported action (e.g., "labeled") → 400
- POST with invalid JSON payload → 400
- POST with null/empty body → 400

**How:**
```java
mockMvc.perform(post("/webhook/pr")
    .contentType(MediaType.APPLICATION_JSON)
    .header("X-Hub-Signature-256", "sha256=" + signature)
    .content(payload))
    .andExpect(status().isOk());
```

### 4.2 Signature Validation

**What to test:**
- HMAC-SHA256 digest matches → true
- Tampered payload → false
- Wrong secret → false
- Null payload/signature/secret → false
- Missing "sha256=" prefix → false

### 4.3 Diff Fetching (manual/GitHub-dependent)

**What to test:**
- Valid PR diff URL → returns raw diff text
- Invalid URL → exception with HTTP status
- Private repo with App installed → fetches successfully
- Private repo without App installation → logs error, returns null

**Test via:**
- `GitHubClient.fetchDiff("https://api.github.com/repos/owner/repo/pulls/123")`
- Mock `WebhookConfig.App` with test credentials

### 4.4 Diff Parsing

**What to test:**
- Unified diff with additions/deletions
- Multi-hunk diff
- Single-line hunk (no comma in `@@ -1 +1,2 @@`)
- New file diff (`@@ -0,0 +1,20 @@`)
- Empty diff → empty list
- Null input → empty list
- Real-world multi-file diff
- Backslash-continuation lines (`\ No newline at end of file`)

### 4.5 AI Agent Review

**Each agent (Security/Logic/CodeStyle/Architecture):**
- Returns correct `getAgentType()`
- LLM result parsed into `ReviewResult` record
- System prompt contains expected domain keywords
- User prompt includes code context
- Static analysis tool integrated (when applicable)
- LLM failure → graceful INFO-level fallback result, not exception
- Tool failure → logged, not breaking review
- Empty/null context → handled gracefully

**Mock ChatClient chain pattern (required for agent tests):**
```java
ChatClient chatClient = mock();
ChatClient.ChatClientRequestSpec requestSpec = mock();
ChatClient.CallResponseSpec callSpec = mock();
ChatClient.PromptSystemSpec systemSpec = mock();
ChatClient.PromptUserSpec userSpec = mock();

when(chatClient.prompt()).thenReturn(requestSpec);
when(requestSpec.system(any(Consumer.class))).thenAnswer(i -> {
    i.getArgument(0, Consumer.class).accept(systemSpec);
    return requestSpec;
});
when(requestSpec.user(any(Consumer.class))).thenAnswer(i -> {
    i.getArgument(0, Consumer.class).accept(userSpec);
    return requestSpec;
});
when(requestSpec.call()).thenReturn(callSpec);
when(callSpec.content()).thenReturn(jsonResult);
```

### 4.6 Static Analysis Tools

**SpotBugsTool — regex patterns to test:**
- Empty catch block: `catch (Exception e) {}`
- String `==` comparison: `if (a == b)`
- `printStackTrace()` call
- `System.out` / `System.err` usage
- TODO / HACK / XXX comments
- Resource leak (non-try-with-resources)
- False positive: try-with-resources NOT flagged

**CheckstyleTool — patterns to test:**
- Missing Javadoc on public method
- Method body >50 lines
- Method with >5 parameters
- Magic numbers (except 0, 1, -1)
- Empty if/else block
- Mixed tabs + spaces indentation
- Edge: empty input, clean code returns empty

**ArchUnitTool — patterns to test:**
- Layer violation: Controller imports Repository class
- Cyclic dependency: Repository imports Controller
- Valid layered architecture: Service imports Repository (OK)
- Empty input

### 4.7 Orchestration

**What to test:**
- All agents' results collected into flat list
- One failing agent → other agents still produce results
- All agents fail → empty results
- Agent returns null result → skipped
- Null context → empty list
- Empty/null agents list → empty list
- Virtual thread parallelism (timing: all agents start + finish)

### 4.8 Result Aggregation

**What to test:**
- Empty/null list → report with 0 counts
- Single result → preserved as-is
- Duplicate (same file/line/category) → highest severity kept
- Different keys → NOT deduplicated (both kept)
- Sort order: CRITICAL first, then by filePath, then lineStart
- Multiple severities → correct counts (critical/major/minor)
- Null file path → handled (not grouped)

### 4.9 False-Positive Filtering

**What to test:**
- Rule not downgraded → pass through unchanged
- CRITICAL downgraded → MAJOR + "[auto-downgraded]" suffix
- MAJOR downgraded → MINOR
- MINOR downgraded → INFO
- INFO downgraded → INFO (no lower)
- Dismissed > accepted → result SUPPRESSED (removed from list)
- Null result list → empty list
- Mixed (downgraded + non-downgraded) → correct filtering
- Agent type null in stats → treat as unknown

### 4.10 Memory / Feedback

**What to test:**
- Store ACCEPTED feedback → INSERT + UPSERT rule_stats
- Store DISMISSED feedback → INSERT + UPSERT + downgrade check
- getRuleStats → returns stats for all rules
- getRuleStats(specific) → returns single rule or null
- isRuleDowngraded → true when dismiss ratio > threshold (default 0.7)
- resetRuleStats → deletes rule from stats
- getRecentFeedback → returns matching records
- FeedbackCollector parse directives:
  - `/feedback accept security SQL_INJECTION` → parsed correctly
  - `/feedback dismiss code-style NAMING src/File.java 42` → with file/line
  - Case insensitive
  - Invalid format → throws

### 4.11 Report Generation

**What to test:**
- Header with PR ID present
- Summary section with severity count table
- Findings by File section (grouped, sorted)
- Agent Summary section
- Empty report → still generates sections
- Null report → empty string
- Markdown escaping of special chars

### 4.12 PR Comment Publishing (manual/GitHub-dependent)

**What to test:**
- Valid GitHub App credentials → comment posted
- Null/blank App → logs warning, skips
- Null report → logs warning, skips
- GitHub API error → caught, logged, no exception propagated
- `formatLineComment` output format: severity, category, title, description, suggestion

---
## 5. Testing Config Values

### 5.1 Application Properties

| Property | Value | Notes |
|----------|-------|-------|
| `github.app.id` | `${secrets.github-app-id}` | GitHub App ID |
| `github.app.private-key` | `${secrets.github-private-key}` | RSA private key |
| `github.app.webhook-secret` | `${secrets.github-webhook-secret}` | Webhook HMAC secret |
| `memory.downgrade-threshold` | 0.7 (default) | False-positive dismiss ratio |

### 5.2 Test Property Overrides

Used in `@SpringBootTest(properties = {...})`:

```java
"github.app.id=test-app-id",
"github.app.private-key=test-private-key",
"github.app.webhook-secret=test-secret",
"spring.ai.openai.api-key=test-key",
// + Exclude DataSource/Flyway autoconfig
```

---
## 6. Test Data Fixtures

### 6.1 Sample Webhook Payload

```json
{
  "action": "opened",
  "pull_request": {
    "title": "Test PR",
    "body": "Test description",
    "head": { "ref": "feature-branch", "sha": "abc123def456" },
    "base": { "ref": "main", "sha": "789012ghi345" },
    "diff_url": "https://api.github.com/repos/owner/repo/pulls/42"
  },
  "repository": { "full_name": "owner/repo" },
  "number": 42
}
```

### 6.2 Sample Unified Diff

```diff
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
```

### 6.3 Sample Agent JSON Response

```json
{
  "severity": "MAJOR",
  "category": "sql-injection",
  "filePath": "src/main/java/com/example/App.java",
  "lineStart": 8,
  "lineEnd": 8,
  "title": "SQL Injection Risk",
  "description": "User input concatenated into SQL query.",
  "suggestion": "Use parameterized queries."
}
```

---
## 7. Known Pre-existing Test Failures

These 3 test files fail but are unrelated to config/auth changes:

| Test | Failure | Root Cause |
|------|---------|------------|
| `AiCodeReviewIntegrationTest.testFullPipelineFromContextToReport` | `assertTrue(dependencyGraph.contains("import"))` | Dependency graph construction doesn't always include "import" string. Test expectation mismatch with `ContextBuilder` logic. |
| `MemoryServiceTest.testStoreFeedbackDismissedTriggersDowngradeCheck` | Mockito `PotentialStubbingProblem` | Test stubs `jdbcTemplate.update()` with wrong args; actual call uses 9 params including timestamp. |
| `FalsePositiveFilterTest` (5 methods) | `IndexOutOfBoundsException` + assertion failures | `FalsePositiveFilter` implementation returns 0 results when downgraded rules exist, but tests expect 1. Logic mismatch between filter and test expectations. |

**To fix these, investigate:**
- `ContextBuilder.build()` — how `dependencyGraph` string is assembled
- `MemoryService.storeFeedback()` — timestamp parameter in SQL
- `FalsePositiveFilter.filter()` — count of results returned under downgrade conditions

---
## 8. CI Pipeline

Defined in `.github/workflows/ci.yml`:

```yaml
steps:
  - mvn clean verify          # Build + test
  - ci/quality-gate.sh report threshold  # Fail on CRITICAL, warn on >10 MAJOR
```

Quality Gate script: `ci/quality-gate.sh <report-path> <threshold>`

---
## 9. Quick Test Cheatsheet

```bash
# Fastest feedback — compile only
mvn compile -q

# Run specific layer tests
mvn test -Dtest="*AgentTest"        # Agent layer
mvn test -Dtest="*ToolTest"         # Static analysis tools
mvn test -Dtest="*Publisher*"       # Report publishing

# Run with coverage (if JaCoCo configured)
mvn test jacoco:report

# Full build (skip known-failing tests)
mvn test -Dtest='!AiCodeReviewIntegrationTest,!MemoryServiceTest,!FalsePositiveFilterTest'
```
