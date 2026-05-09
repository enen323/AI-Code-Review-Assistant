package com.ai.code.review;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Basic application context test.
 *
 * Disabled by default as it requires a running PostgreSQL instance.
 * Enable when a local database is available for integration testing.
 */
@SpringBootTest
@Disabled("Requires database connection - enable when PostgreSQL is running")
class AiCodeReviewAssistantApplicationTests {

    @Test
    void contextLoads() {
    }
}
