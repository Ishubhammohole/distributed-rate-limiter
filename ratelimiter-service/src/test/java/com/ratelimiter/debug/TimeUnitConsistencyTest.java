package com.ratelimiter.debug;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.strategy.TokenBucketRateLimiterStrategy;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test to verify time unit consistency and refill rate calculations.
 * This test demonstrates the time-based refill issue and validates fixes.
 */
@SpringBootTest
@Testcontainers
class TimeUnitConsistencyTest {

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
    private TokenBucketRateLimiterStrategy strategy;

    @BeforeEach
    void setUp() {
        // Clear Redis before each test
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void demonstrateRefillRateIssue() {
        System.out.println("=== Demonstrating Refill Rate Issue ===");
        
        // Use 60s window - this creates a very small refill rate
        RateLimitRequest request = createRequest("refill-demo", 5L, "60s", 1);
        
        // Calculate expected refill rate
        long windowMs = 60 * 1000; // 60 seconds = 60,000 ms
        double refillRatePerMs = (double) 5 / windowMs;
        long refillRateMicroPerMs = Math.round(refillRatePerMs * 1_000_000);
        
        System.out.printf("Window: 60s = %d ms\n", windowMs);
        System.out.printf("Refill rate: %.10f tokens/ms\n", refillRatePerMs);
        System.out.printf("Refill rate: %d microtokens/ms\n", refillRateMicroPerMs);
        System.out.printf("Even 5ms gap = %d microtokens refilled\n", 5 * refillRateMicroPerMs);
        
        // Exhaust tokens rapidly
        long startTime = System.currentTimeMillis();
        RateLimitResponse r1 = strategy.execute(request);
        long time1 = System.currentTimeMillis();
        RateLimitResponse r2 = strategy.execute(request);
        long time2 = System.currentTimeMillis();
        RateLimitResponse r3 = strategy.execute(request);
        long time3 = System.currentTimeMillis();
        
        System.out.printf("Request 1: %dms, remaining=%d\n", time1 - startTime, r1.getRemaining());
        System.out.printf("Request 2: %dms, remaining=%d (gap: %dms)\n", time2 - startTime, r2.getRemaining(), time2 - time1);
        System.out.printf("Request 3: %dms, remaining=%d (gap: %dms)\n", time3 - startTime, r3.getRemaining(), time3 - time2);
        
        // Show that even small gaps cause refill
        long gap1 = time2 - time1;
        long gap2 = time3 - time2;
        long expectedRefill1 = gap1 * refillRateMicroPerMs;
        long expectedRefill2 = gap2 * refillRateMicroPerMs;
        
        System.out.printf("Expected refill from gap1 (%dms): %d microtokens\n", gap1, expectedRefill1);
        System.out.printf("Expected refill from gap2 (%dms): %d microtokens\n", gap2, expectedRefill2);
    }

    @Test
    void testWithLargerWindow() {
        System.out.println("=== Testing with Larger Window (Less Refill) ===");
        
        // Use 1 hour window - much smaller refill rate
        RateLimitRequest request = createRequest("large-window", 5L, "1h", 1);
        
        // Calculate refill rate
        long windowMs = 60 * 60 * 1000; // 1 hour = 3,600,000 ms
        double refillRatePerMs = (double) 5 / windowMs;
        long refillRateMicroPerMs = Math.round(refillRatePerMs * 1_000_000);
        
        System.out.printf("Window: 1h = %d ms\n", windowMs);
        System.out.printf("Refill rate: %.10f tokens/ms\n", refillRatePerMs);
        System.out.printf("Refill rate: %d microtokens/ms\n", refillRateMicroPerMs);
        System.out.printf("Even 100ms gap = %d microtokens refilled\n", 100 * refillRateMicroPerMs);
        
        // Exhaust tokens rapidly
        RateLimitResponse r1 = strategy.execute(request);
        RateLimitResponse r2 = strategy.execute(request);
        RateLimitResponse r3 = strategy.execute(request);
        RateLimitResponse r4 = strategy.execute(request);
        RateLimitResponse r5 = strategy.execute(request);
        RateLimitResponse r6 = strategy.execute(request); // Should be denied
        
        System.out.printf("Request 1: allowed=%s, remaining=%d\n", r1.isAllowed(), r1.getRemaining());
        System.out.printf("Request 2: allowed=%s, remaining=%d\n", r2.isAllowed(), r2.getRemaining());
        System.out.printf("Request 3: allowed=%s, remaining=%d\n", r3.isAllowed(), r3.getRemaining());
        System.out.printf("Request 4: allowed=%s, remaining=%d\n", r4.isAllowed(), r4.getRemaining());
        System.out.printf("Request 5: allowed=%s, remaining=%d\n", r5.isAllowed(), r5.getRemaining());
        System.out.printf("Request 6: allowed=%s, remaining=%d\n", r6.isAllowed(), r6.getRemaining());
        
        // With 1-hour window, refill should be negligible for rapid requests
        assertThat(r1.getRemaining()).isEqualTo(4);
        assertThat(r2.getRemaining()).isEqualTo(3);
        assertThat(r3.getRemaining()).isEqualTo(2);
        assertThat(r4.getRemaining()).isEqualTo(1);
        assertThat(r5.getRemaining()).isEqualTo(0);
        assertThat(r6.isAllowed()).isFalse();
        assertThat(r6.getRemaining()).isEqualTo(0);
    }

    @Test
    void testWindowParsing() {
        System.out.println("=== Testing Window Parsing ===");
        
        // Test different window formats
        String[] windows = {"1s", "60s", "5m", "1h"};
        long[] expectedMs = {1000L, 60000L, 300000L, 3600000L};
        
        for (int i = 0; i < windows.length; i++) {
            RateLimitRequest request = createRequest("window-test-" + i, 10L, windows[i], 1);
            
            // This will trigger window parsing internally
            RateLimitResponse response = strategy.execute(request);
            
            System.out.printf("Window '%s' -> expected %d ms, response: allowed=%s, remaining=%d\n", 
                windows[i], expectedMs[i], response.isAllowed(), response.getRemaining());
            
            // All should be allowed (first request)
            assertThat(response.isAllowed()).isTrue();
            assertThat(response.getRemaining()).isEqualTo(9); // 10 - 1 = 9
        }
    }

    @Test
    void verifyMillisecondTimestamps() {
        System.out.println("=== Verifying Millisecond Timestamps ===");
        
        RateLimitRequest request = createRequest("timestamp-test", 3L, "1h", 1);
        String redisKey = "rl:timestamp-test:tb";
        
        // Capture time once to avoid timing drift
        long now = System.currentTimeMillis();
        strategy.execute(request);
        
        // Check Redis timestamp
        String lastRefillStr = (String) redisTemplate.opsForHash().get(redisKey, "last_refill_ms");
        long lastRefillMs = Long.parseLong(lastRefillStr);
        
        System.out.printf("Request time: %d ms\n", now);
        System.out.printf("Redis timestamp: %d ms\n", lastRefillMs);
        
        // Redis timestamp should be close to request time (within 100ms tolerance)
        long timeDiff = Math.abs(lastRefillMs - now);
        System.out.printf("Time difference: %d ms\n", timeDiff);
        
        assertThat(timeDiff).isLessThanOrEqualTo(100L);
        System.out.println("✅ Redis timestamp is within acceptable range");
    }

    private RateLimitRequest createRequest(String key, Long limit, String window, int cost) {
        RateLimitRequest request = new RateLimitRequest();
        request.setKey(key);
        request.setAlgorithm("token_bucket");
        request.setLimit(limit);
        request.setWindow(window);
        request.setCost(cost);
        return request;
    }
}