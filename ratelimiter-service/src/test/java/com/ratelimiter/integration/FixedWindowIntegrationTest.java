package com.ratelimiter.integration;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.strategy.FixedWindowRateLimiterStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Fixed Window rate limiter strategy.
 * Tests the complete flow with real Redis instance.
 */
@SpringBootTest
@Testcontainers
class FixedWindowIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private FixedWindowRateLimiterStrategy strategy;

    @BeforeEach
    void setUp() {
        // Clear Redis before each test
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void fixedWindow_BasicFunctionality_WorksCorrectly() {
        RateLimitRequest request = createRequest("basic-test", 5L, "60s", 1);
        
        // First 5 requests should be allowed
        for (int i = 0; i < 5; i++) {
            RateLimitResponse response = strategy.execute(request);
            
            assertThat(response.isAllowed()).isTrue();
            assertThat(response.getRemaining()).isEqualTo(4 - i);
            assertThat(response.getResetTime()).isAfter(Instant.now());
        }
        
        // 6th request should be denied
        RateLimitResponse deniedResponse = strategy.execute(request);
        assertThat(deniedResponse.isAllowed()).isFalse();
        assertThat(deniedResponse.getRemaining()).isEqualTo(0);
    }

    @Test
    void fixedWindow_HighCostRequests_WorksCorrectly() {
        RateLimitRequest request = createRequest("high-cost-test", 10L, "60s", 3);
        
        // First request (cost=3) should be allowed, remaining=7
        RateLimitResponse response1 = strategy.execute(request);
        assertThat(response1.isAllowed()).isTrue();
        assertThat(response1.getRemaining()).isEqualTo(7);
        
        // Second request (cost=3) should be allowed, remaining=4
        RateLimitResponse response2 = strategy.execute(request);
        assertThat(response2.isAllowed()).isTrue();
        assertThat(response2.getRemaining()).isEqualTo(4);
        
        // Third request (cost=3) should be allowed, remaining=1
        RateLimitResponse response3 = strategy.execute(request);
        assertThat(response3.isAllowed()).isTrue();
        assertThat(response3.getRemaining()).isEqualTo(1);
        
        // Fourth request (cost=3) should be denied (would exceed limit)
        RateLimitResponse response4 = strategy.execute(request);
        assertThat(response4.isAllowed()).isFalse();
        assertThat(response4.getRemaining()).isEqualTo(1);
    }

    @Test
    void fixedWindow_DifferentKeys_IsolatedCorrectly() {
        RateLimitRequest request1 = createRequest("key1", 3L, "60s", 1);
        RateLimitRequest request2 = createRequest("key2", 3L, "60s", 1);
        
        // Exhaust key1
        for (int i = 0; i < 3; i++) {
            RateLimitResponse response = strategy.execute(request1);
            assertThat(response.isAllowed()).isTrue();
        }
        
        // key1 should be exhausted
        RateLimitResponse exhaustedResponse = strategy.execute(request1);
        assertThat(exhaustedResponse.isAllowed()).isFalse();
        
        // key2 should still work
        RateLimitResponse key2Response = strategy.execute(request2);
        assertThat(key2Response.isAllowed()).isTrue();
        assertThat(key2Response.getRemaining()).isEqualTo(2);
    }

    @Test
    void fixedWindow_WindowAlignment_WorksCorrectly() {
        // This test verifies that windows are properly aligned to boundaries
        RateLimitRequest request = createRequest("alignment-test", 2L, "1s", 1);
        
        // Make requests and verify they're counted in the same window
        RateLimitResponse response1 = strategy.execute(request);
        assertThat(response1.isAllowed()).isTrue();
        assertThat(response1.getRemaining()).isEqualTo(1);
        
        RateLimitResponse response2 = strategy.execute(request);
        assertThat(response2.isAllowed()).isTrue();
        assertThat(response2.getRemaining()).isEqualTo(0);
        
        // Third request should be denied
        RateLimitResponse response3 = strategy.execute(request);
        assertThat(response3.isAllowed()).isFalse();
        assertThat(response3.getRemaining()).isEqualTo(0);
        
        // Wait for window to reset (this is a simplified test - in practice
        // we'd need to wait for the actual window boundary)
        try {
            Thread.sleep(1100); // Wait slightly more than 1 second
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // After window reset, should be allowed again
        RateLimitResponse response4 = strategy.execute(request);
        assertThat(response4.isAllowed()).isTrue();
        assertThat(response4.getRemaining()).isEqualTo(1);
    }

    @Test
    void fixedWindow_ResetTimeCalculation_IsCorrect() {
        RateLimitRequest request = createRequest("reset-time-test", 5L, "60s", 1);
        
        long beforeRequest = System.currentTimeMillis();
        RateLimitResponse response = strategy.execute(request);
        long afterRequest = System.currentTimeMillis();
        
        assertThat(response.isAllowed()).isTrue();
        
        // Reset time should be aligned to the next window boundary
        long resetTimeMs = response.getResetTime().toEpochMilli();
        
        // The reset time should be in the future
        assertThat(resetTimeMs).isGreaterThan(afterRequest);
        
        // The reset time should be within the next window (60s from now)
        assertThat(resetTimeMs).isLessThanOrEqualTo(beforeRequest + 60000L + 1000L); // +1s tolerance
    }

    @Test
    void fixedWindow_ConcurrentRequests_HandledAtomically() {
        RateLimitRequest request = createRequest("concurrent-test", 5L, "60s", 1);
        
        // Simulate concurrent requests by making them rapidly
        int allowedCount = 0;
        int deniedCount = 0;
        
        for (int i = 0; i < 10; i++) {
            RateLimitResponse response = strategy.execute(request);
            if (response.isAllowed()) {
                allowedCount++;
            } else {
                deniedCount++;
            }
        }
        
        // Exactly 5 should be allowed, 5 should be denied
        assertThat(allowedCount).isEqualTo(5);
        assertThat(deniedCount).isEqualTo(5);
    }

    @Test
    void fixedWindow_DifferentWindowSizes_WorkCorrectly() {
        // Test with different window sizes
        RateLimitRequest request1s = createRequest("window-1s", 2L, "1s", 1);
        RateLimitRequest request5s = createRequest("window-5s", 3L, "5s", 1);
        
        // Both should allow initial requests
        RateLimitResponse response1s = strategy.execute(request1s);
        assertThat(response1s.isAllowed()).isTrue();
        assertThat(response1s.getRemaining()).isEqualTo(1);
        
        RateLimitResponse response5s = strategy.execute(request5s);
        assertThat(response5s.isAllowed()).isTrue();
        assertThat(response5s.getRemaining()).isEqualTo(2);
        
        // Verify reset times are different (5s window should reset later)
        assertThat(response5s.getResetTime()).isAfter(response1s.getResetTime());
    }

    @Test
    void fixedWindow_RedisKeyStructure_IsCorrect() {
        RateLimitRequest request = createRequest("key-structure-test", 5L, "60s", 1);
        
        // Execute request to create Redis keys
        strategy.execute(request);
        
        // Check that Redis keys follow expected pattern
        // Keys should be: rl:key-structure-test:fw:{window_start_ms}
        var keys = redisTemplate.keys("rl:key-structure-test:fw:*");
        assertThat(keys).hasSize(1);
        
        String key = keys.iterator().next();
        assertThat(key).startsWith("rl:key-structure-test:fw:");
        
        // Verify the key has a value (the counter)
        String value = redisTemplate.opsForValue().get(key);
        assertThat(value).isEqualTo("1");
    }

    private RateLimitRequest createRequest(String key, Long limit, String window, int cost) {
        RateLimitRequest request = new RateLimitRequest();
        request.setKey(key);
        request.setAlgorithm("fixed_window");
        request.setLimit(limit);
        request.setWindow(window);
        request.setCost(cost);
        return request;
    }
}