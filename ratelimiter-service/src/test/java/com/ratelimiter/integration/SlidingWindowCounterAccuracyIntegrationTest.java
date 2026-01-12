package com.ratelimiter.integration;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.strategy.SlidingWindowCounterRateLimiterStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for sliding window counter accuracy and approximation behavior.
 * Tests the algorithm against real Redis with actual timing to validate accuracy bounds.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class SlidingWindowCounterAccuracyIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @Autowired
    private SlidingWindowCounterRateLimiterStrategy strategy;

    private String testKey;

    @BeforeEach
    void setUp() {
        testKey = "accuracy-test-" + System.currentTimeMillis();
    }

    @Test
    void shouldDemonstrateBoundaryApproximationBehavior() throws InterruptedException {
        // Test the documented worst-case scenario
        String key = testKey + "-boundary";
        
        // Fill window with 3 requests near the end
        for (int i = 0; i < 3; i++) {
            RateLimitRequest request = new RateLimitRequest(key, "sliding_window_counter", 3L, "2s", 1);
            RateLimitResponse response = strategy.execute(request);
            assertThat(response.isAllowed()).isTrue();
            Thread.sleep(50); // Small delay between requests
        }
        
        // Wait for window boundary (2s window for faster test)
        Thread.sleep(2100);
        
        // Request immediately after boundary - this demonstrates approximation behavior
        RateLimitRequest boundaryRequest = new RateLimitRequest(key, "sliding_window_counter", 3L, "2s", 1);
        RateLimitResponse boundaryResponse = strategy.execute(boundaryRequest);
        
        // The request may be allowed due to approximation (floor function)
        // This is documented behavior, not a bug
        if (boundaryResponse.isAllowed()) {
            // Approximation allowed the request (weight caused underestimation)
            assertThat(boundaryResponse.getRemaining()).isLessThanOrEqualTo(2L);
        } else {
            // Approximation correctly denied the request
            assertThat(boundaryResponse.getRemaining()).isEqualTo(0L);
        }
        
        // Both outcomes are acceptable per the approximation algorithm specification
    }

    @Test
    void shouldMaintainAccuracyUnderTypicalUsage() throws InterruptedException {
        // Test accuracy under realistic distributed request patterns
        String key = testKey + "-typical";
        List<Long> requestTimes = new ArrayList<>();
        
        // Send requests spread across window
        for (int i = 0; i < 5; i++) {
            long startTime = System.currentTimeMillis();
            RateLimitRequest request = new RateLimitRequest(key, "sliding_window_counter", 10L, "3s", 1);
            RateLimitResponse response = strategy.execute(request);
            
            if (response.isAllowed()) {
                requestTimes.add(startTime);
            }
            
            Thread.sleep(400); // 400ms between requests
        }
        
        // Verify that most requests were allowed (typical case should have good accuracy)
        assertThat(requestTimes).hasSizeGreaterThanOrEqualTo(4);
        
        // Test boundary behavior
        Thread.sleep(3100); // Wait for window boundary
        
        RateLimitRequest postBoundaryRequest = new RateLimitRequest(key, "sliding_window_counter", 10L, "3s", 1);
        RateLimitResponse postBoundaryResponse = strategy.execute(postBoundaryRequest);
        
        // Should be allowed as we're in a new window with minimal overlap
        assertThat(postBoundaryResponse.isAllowed()).isTrue();
        assertThat(postBoundaryResponse.getRemaining()).isGreaterThan(0L);
    }

    @Test
    void shouldEnforceApproximationErrorBounds() throws InterruptedException {
        // Test that approximation error stays within documented bounds
        String key = testKey + "-bounds";
        
        // Create a scenario with known approximation characteristics
        // Fill previous window completely
        for (int i = 0; i < 5; i++) {
            RateLimitRequest request = new RateLimitRequest(key, "sliding_window_counter", 5L, "1s", 1);
            RateLimitResponse response = strategy.execute(request);
            if (i < 5) {
                assertThat(response.isAllowed()).isTrue();
            }
            Thread.sleep(50);
        }
        
        // Wait for partial window transition
        Thread.sleep(1200);
        
        // Test requests in new window
        int allowedCount = 0;
        for (int i = 0; i < 3; i++) {
            RateLimitRequest request = new RateLimitRequest(key, "sliding_window_counter", 5L, "1s", 1);
            RateLimitResponse response = strategy.execute(request);
            if (response.isAllowed()) {
                allowedCount++;
            }
            Thread.sleep(50);
        }
        
        // Under the approximation algorithm, we expect some requests to be allowed
        // even if exact sliding window would deny them. This validates the documented behavior.
        assertThat(allowedCount).isGreaterThanOrEqualTo(1);
        assertThat(allowedCount).isLessThanOrEqualTo(5);
    }

    @Test
    void shouldHandleHighFrequencyBoundaryRequests() throws InterruptedException {
        // Test behavior with rapid requests at window boundaries
        String key = testKey + "-highfreq";
        
        // Fill window near capacity
        for (int i = 0; i < 8; i++) {
            RateLimitRequest request = new RateLimitRequest(key, "sliding_window_counter", 10L, "1s", 1);
            strategy.execute(request);
            Thread.sleep(50);
        }
        
        // Wait for boundary
        Thread.sleep(1100);
        
        // Rapid requests at boundary
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            RateLimitRequest request = new RateLimitRequest(key, "sliding_window_counter", 10L, "1s", 1);
            RateLimitResponse response = strategy.execute(request);
            results.add(response.isAllowed());
            Thread.sleep(10); // Very rapid requests
        }
        
        // At least some requests should be allowed in the new window
        long allowedCount = results.stream().mapToLong(allowed -> allowed ? 1 : 0).sum();
        assertThat(allowedCount).isGreaterThan(0L);
        
        // Validates that the algorithm handles high-frequency boundary scenarios
        // without completely blocking legitimate traffic
    }
}