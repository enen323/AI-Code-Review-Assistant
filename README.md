# AI Code Review Assistant

[![CI Build](https://github.com/your-org/ai-code-review-assistant/actions/workflows/ci.yml/badge.svg)](https://github.com/your-org/ai-code-review-assistant/actions/workflows/ci.yml)

A multi-agent AI code review system that automatically analyzes pull requests for security vulnerabilities, logic errors, code style violations, and architectural issues. Built with Spring Boot 3.4, Java 21, and PostgreSQL 16 with PGVector.

## Features

- **4 Review Agents**: Security, Logic, CodeStyle, and Architecture analysis
- **Static Analysis Tools**: Integrates SpotBugs, Checkstyle, and ArchUnit
- **Memory & Learning**: PGVector-based feedback loop that learns from false positives
- **Markdown Reports**: Structured reports posted as GitHub PR comments
- **CI/CD Ready**: GitHub Actions workflow with quality gate enforcement

## Prerequisites

- **JDK 21** (Temurin recommended)
- **Docker** (Docker Compose v2)
- **Maven** 3.9+ (or use the Maven Wrapper)
- An AI provider API key (OpenAI / compatible)

## Quick Start

### 1. Clone and configure

```bash
git clone <repository-url>
cd AI Code Review Assistant
```

Create a `.env` file (or export environment variables):

```bash
AI_API_KEY=sk-your-api-key-here
GITHUB_API_TOKEN=ghp_your-github-token       # optional
GITHUB_WEBHOOK_SECRET=your-webhook-secret     # optional
```

### 2. Start with Docker Compose

```bash
docker compose up -d
```

This starts:
- PostgreSQL 16 with PGVector on port 5432
- The AI Code Review application on port 8080

### 3. Build and run without Docker

```bash
mvn clean package -DskipTests
java -jar target/ai-code-review-assistant-0.0.1-SNAPSHOT.jar
```

## How to Run Tests

```bash
# Run all tests
mvn clean test

# Run tests with CI profile (requires PostgreSQL on localhost:5432)
mvn clean verify -Dspring.profiles.active=ci

# Skip tests during build
mvn clean package -DskipTests
```

The test suite includes:
- Unit tests for all 4 review agents
- Tools tests (SpotBugs, Checkstyle, ArchUnit)
- Aggregation and report generation tests
- Memory and feedback loop tests
- Webhook controller and signature validation tests

## CI/CD Pipeline

The project includes a GitHub Actions workflow (`.github/workflows/ci.yml`) that:

1. **Checkout** the repository
2. **Set up JDK 21** (Temurin) with Maven caching
3. **Start PostgreSQL** with PGVector as a service container
4. **Build** with `mvn clean verify` using the `ci` profile
5. **Quality Gate** — parses the review report and enforces:
   - **FAIL** if any CRITICAL findings exist
   - **WARN** if MAJOR findings exceed the configurable threshold (default: 10)
   - **PASS** otherwise
6. **Upload** build artifacts (reports, logs)

### Quality Gate Script

The quality gate can also be run locally:

```bash
./ci/quality-gate.sh target/site/review-report.md 10
```

## Project Structure

```
├── .github/workflows/    # CI/CD workflows
├── ci/                   # CI scripts (quality gate)
├── src/
│   ├── main/java/com/ai/code/review/
│   │   ├── agent/           # Review agents (security, logic, codestyle, architecture)
│   │   ├── aggregation/     # Result aggregation
│   │   ├── config/          # Application configuration
│   │   ├── context/         # PR context and diff parsing
│   │   ├── memory/          # Feedback loop and PGVector storage
│   │   ├── model/           # Domain models
│   │   ├── orchestration/   # Orchestrator and event listeners
│   │   ├── report/          # Report generation and PR publishing
│   │   ├── tool/            # Static analysis tools (SpotBugs, Checkstyle, ArchUnit)
│   │   └── trigger/         # Webhook receiver
│   ├── main/resources/      # Application properties, Flyway migrations
│   └── test/                # Test suite
├── docker-compose.yml       # Docker Compose with PostgreSQL + app
└── pom.xml                  # Maven build
```

## Configuration

| Variable                 | Required | Description                              |
|--------------------------|----------|------------------------------------------|
| `AI_API_KEY`             | Yes      | AI provider API key                      |
| `DB_USERNAME`            | No       | PostgreSQL username (default: postgres)  |
| `DB_PASSWORD`            | No       | PostgreSQL password (default: postgres)  |
| `GITHUB_API_TOKEN`       | No       | GitHub token for PR comments             |
| `GITHUB_WEBHOOK_SECRET`  | No       | Webhook secret for payload verification  |

## License

This project is licensed under the MIT License.
