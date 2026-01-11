package com.ratelimiter.debug;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.strategy.TokenBucketRateLimiterStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test to verify Lua script state update logic.
 * This test ensures that the Lua script:
 * 1. Always persists updated token count
 * 2. Always persists updated lastRefill timestamp
 * 3. Correctly handles refill math
 * 4. Never exceeds capacity
 * 5. Properly applies cost
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class LuaScriptStateTest {

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
    void verifyStateAlwaysPersisted() {
        System.out.println("=== Verifying State Always Persisted ===");
        
        String testKey = "state-persist-test";
        RateLimitRequest request = createRequest(testKey, 5L, "1h", 1);
        String redisKey = "rl:" + testKey + ":tb";
        
        // Make multiple requests and verify state is persisted after each
        for (int i = 1; i <= 3; i++) {
            System.out.printf("\n--- Request %d ---\n", i);
            
            // Get state before request
            Map<Object, Object> stateBefore = redisTemplate.opsForHash().entries(redisKey);
            System.out.printf("Before: %s\n", stateBefore.isEmpty() ? "empty" : stateBefore);
            
            // Make request
            RateLimitResponse response = strategy.execute(request);
            System.out.printf("Response: allowed=%s, remaining=%d\n", response.isAllowed(), response.getRemaining());
            
            // Get state after request
            Map<Object, Object> stateAfter = redisTemplate.opsForHash().entries(redisKey);
            System.out.printf("After: %s\n", stateAfter);
            
            // Verify state exists and has required fields
            assertThat(stateAfter).isNotEmpty();
            assertThat(stateAfter).containsKeys("tokens", "last_refill_ms");
            
            String tokens = (String) stateAfter.get("tokens");
            String lastRefill = (String) stateAfter.get("last_refill_ms");
            
            assertThat(tokens).isNotNull();
            assertThat(lastRefill).isNotNull();
            
            long tokensValue = Long.parseLong(tokens);
            long lastRefillValue = Long.parseLong(lastRefill);
            
            assertThat(tokensValue).isGreaterThanOrEqualTo(0);
            assertThat(lastRefillValue).isGreaterThan(0);
            
            System.out.printf("✅ State persisted: tokens=%d, last_refill_ms=%d\n", tokensValue, lastRefillValue);
        }
    }

    @Test
    void verifyCapacityNeverExceeded() {
        System.out.println("=== Verifying Capacity Never Exceeded ===");
        
        String testKey = "capacity-test";
        long capacity = 3L;
        RateLimitRequest request = createRequest(testKey, capacity, "1h", 1);
        String redisKey = "rl:" + testKey + ":tb";
        
        // Make initial request to establish state
        RateLimitResponse response1 = strategy.execute(request);
        System.out.printf("Initial request: remaining=%d\n", response1.getRemaining());
        
        // Wait a bit to allow some refill (but not enough to exceed capacity)
        try {
            Thread.sleep(100); // 100ms
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Make another request
        RateLimitResponse response2 = strategy.execute(request);
        System.out.printf("After 100ms: remaining=%d\n", response2.getRemaining());
        
        // Check Redis state
        Map<Object, Object> state = redisTemplate.opsForHash().entries(redisKey);
        String tokens = (String) state.get("tokens");
        long tokensValue = Long.parseLong(tokens);
        long maxTokens = capacity * 1_000_000; // Convert to microtokens
        
        System.out.printf("Redis tokens: %d microtokens (max: %d)\n", tokensValue, maxTokens);
        
        // Verify tokens never exceed capacity
        assertThat(tokensValue).isLessThanOrEqualTo(maxTokens);
        System.out.println("✅ Tokens never exceed capacity");
        
        // Verify response remaining never exceeds capacity
        assertThat(response1.getRemaining()).isLessThanOrEqualTo(capacity);
        assertThat(response2.getRemaining()).isLessThanOrEqualTo(capacity);
        System.out.println("✅ Response remaining never exceeds capacity");
    }

    @Test
    void verifyCostProperlyApplied() {
        System.out.println("=== Verifying Cost Properly Applied ===");
        
        String testKey = "cost-test";
        RateLimitRequest request1 = createRequest(testKey, 10L, "1h", 1); // cost 1
        RateLimitRequest request3 = createRequest(testKey, 10L, "1h", 3); // cost 3
        String redisKey = "rl:" + testKey + ":tb";
        
        // Make request with cost 1
        RateLimitResponse response1 = strategy.execute(request1);
        System.out.printf("Cost 1: remaining=%d\n", response1.getRemaining());
        assertThat(response1.getRemaining()).isEqualTo(9); // 10 - 1 = 9
        
        // Check Redis state
        Map<Object, Object> state1 = redisTemplate.opsForHash().entries(redisKey);
        long tokens1 = Long.parseLong((String) state1.get("tokens"));
        System.out.printf("After cost 1: %d microtokens\n", tokens1);
        
        // Make request with cost 3
        RateLimitResponse response3 = strategy.execute(request3);
        System.out.printf("Cost 3: remaining=%d\n", response3.getRemaining());
        assertThat(response3.getRemaining()).isEqualTo(6); // 9 - 3 = 6
        
        // Check Redis state
        Map<Object, Object> state3 = redisTemplate.opsForHash().entries(redisKey);
        long tokens3 = Long.parseLong((String) state3.get("tokens"));
        System.out.printf("After cost 3: %d microtokens\n", tokens3);
        
        // Verify cost was properly applied (allowing for minimal refill)
        long expectedDecrease = 3 * 1_000_000; // 3 tokens in microtokens
        long actualDecrease = tokens1 - tokens3;
        System.out.printf("Expected decrease: %d, Actual decrease: %d\n", expectedDecrease, actualDecrease);
        
        // Allow for small refill during the request gap
        assertThat(actualDecrease).isBetween(expectedDecrease - 1000, expectedDecrease + 1000);
        System.out.println("✅ Cost properly applied");
    }

    @Test
    void verifyRefillMathCorrectness() {
        System.out.println("=== Verifying Refill Math Correctness ===");
        
        String testKey = "refill-math-test";
        // Use 1 second window for predictable refill rate
        RateLimitRequest request = createRequest(testKey, 10L, "1s", 1);
        String redisKey = "rl:" + testKey + ":tb";
        
        // Consume some tokens
        RateLimitResponse response1 = strategy.execute(request);
        RateLimitResponse response2 = strategy.execute(request);
        System.out.printf("After consuming 2 tokens: remaining=%d\n", response2.getRemaining());
        
        // Get initial state
        Map<Object, Object> stateBefore = redisTemplate.opsForHash().entries(redisKey);
        long tokensBefore = Long.parseLong((String) stateBefore.get("tokens"));
        long timestampBefore = Long.parseLong((String) stateBefore.get("last_refill_ms"));
        
        // Wait for refill (1.1 seconds to ensure refill)
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Make another request to trigger refill calculation
        RateLimitResponse response3 = strategy.execute(request);
        System.out.printf("After 1.1s wait: remaining=%d\n", response3.getRemaining());
        
        // Get state after refill
        Map<Object, Object> stateAfter = redisTemplate.opsForHash().entries(redisKey);
        long tokensAfter = Long.parseLong((String) stateAfter.get("tokens"));
        long timestampAfter = Long.parseLong((String) stateAfter.get("last_refill_ms"));
        
        System.out.printf("Tokens: %d -> %d (change: %d)\n", tokensBefore, tokensAfter, tokensAfter - tokensBefore);
        System.out.printf("Timestamp: %d -> %d (elapsed: %dms)\n", timestampBefore, timestampAfter, timestampAfter - timestampBefore);
        
        // Verify timestamp was updated
        assertThat(timestampAfter).isGreaterThan(timestampBefore);
        
        // Verify tokens increased due to refill (should be close to full capacity after 1+ second)
        // With 10 tokens per second and 1.1 second wait, should refill significantly
        assertThat(tokensAfter).isGreaterThan(tokensBefore);
        
        // Should have refilled to near capacity (allowing for the 1 token consumed in response3)
        long expectedRemaining = 9; // Should be close to 9 (10 - 1 consumed)
        assertThat(response3.getRemaining()).isGreaterThanOrEqualTo(expectedRemaining - 1);
        
        System.out.println("✅ Refill math working correctly");
    }

    @Test
    void verifyTimestampAlwaysUpdated() {
        System.out.println("=== Verifying Timestamp Always Updated ===");
        
        String testKey = "timestamp-update-test";
        RateLimitRequest request = createRequest(testKey, 5L, "1h", 1);
        String redisKey = "rl:" + testKey + ":tb";
        
        long previousTimestamp = 0;
        
        for (int i = 1; i <= 3; i++) {
            // Small delay between requests
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            strategy.execute(request);
            
            Map<Object, Object> state = redisTemplate.opsForHash().entries(redisKey);
            long currentTimestamp = Long.parseLong((String) state.get("last_refill_ms"));
            
            System.out.printf("Request %d timestamp: %d\n", i, currentTimestamp);
            
            if (i > 1) {
                assertThat(currentTimestamp).isGreaterThanOrEqualTo(previousTimestamp);
                System.out.printf("✅ Timestamp %d >= previous %d\n", currentTimestamp, previousTimestamp);
            }
            
            previousTimestamp = currentTimestamp;
        }
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