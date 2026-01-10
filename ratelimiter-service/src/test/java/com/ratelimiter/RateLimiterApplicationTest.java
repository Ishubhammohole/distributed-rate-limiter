package com.ratelimiter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Basic application context test.
 * Ensures the Spring Boot application starts correctly.
 */
@SpringBootTest
@ActiveProfiles("test")
class RateLimiterApplicationTest {

    @Test
    void contextLoads() {
        // This test will pass if the application context loads successfully
    }
}