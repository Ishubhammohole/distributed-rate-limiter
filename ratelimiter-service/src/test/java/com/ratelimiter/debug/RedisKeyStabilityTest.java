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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test to verify Redis key stability and token persistence.
 * This test ensures that:
 * 1. The same Redis key is used for identical requests
 * 2. Token values are actually persisted and updated in Redis
 * 3. Timestamps are correctly stored and updated
 */
@SpringBootTest
@Testcontainers
class RedisKeyStabilityTest {

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
    void verifyRedisKeyConsistency() {
        System.out.println("=== Verifying Redis Key Consistency ===");
        
        String testKey = "stability-test-key";
        RateLimitRequest request = createRequest(testKey, 5L, "60s", 1);
        String expectedRedisKey = "rl:" + testKey + ":tb";
        
        System.out.printf("Expected Redis key: %s\n", expectedRedisKey);
        
        // Verify key doesn't exist initially
        assertThat(redisTemplate.hasKey(expectedRedisKey)).isFalse();
        System.out.println("✅ Key doesn't exist initially");
        
        // Make first request
        RateLimitResponse response1 = strategy.execute(request);
        System.out.printf("Request 1: allowed=%s, remaining=%d\n", response1.isAllowed(), response1.getRemaining());
        
        // Verify key now exists
        assertThat(redisTemplate.hasKey(expectedRedisKey)).isTrue();
        System.out.println("✅ Key exists after first request");
        
        // Get Redis state after first request
        Map<Object, Object> state1 = redisTemplate.opsForHash().entries(expectedRedisKey);
        String tokens1 = (String) state1.get("tokens");
        String lastRefill1 = (String) state1.get("last_refill_ms");
        System.out.printf("After request 1: tokens=%s, last_refill_ms=%s\n", tokens1, lastRefill1);
        
        // Make second request with IDENTICAL parameters
        RateLimitResponse response2 = strategy.execute(request);
        System.out.printf("Request 2: allowed=%s, remaining=%d\n", response2.isAllowed(), response2.getRemaining());
        
        // Verify same key is still used
        assertThat(redisTemplate.hasKey(expectedRedisKey)).isTrue();
        System.out.println("✅ Same key used for identical request");
        
        // Get Redis state after second request
        Map<Object, Object> state2 = redisTemplate.opsForHash().entries(expectedRedisKey);
        String tokens2 = (String) state2.get("tokens");
        String lastRefill2 = (String) state2.get("last_refill_ms");
        System.out.printf("After request 2: tokens=%s, last_refill_ms=%s\n", tokens2, lastRefill2);
        
        // Verify tokens decreased
        long tokensValue1 = Long.parseLong(tokens1);
        long tokensValue2 = Long.parseLong(tokens2);
        assertThat(tokensValue2).isLessThan(tokensValue1);
        System.out.printf("✅ Tokens decreased: %d -> %d\n", tokensValue1, tokensValue2);
        
        // Verify timestamp updated (should be >= previous)
        long timestampValue1 = Long.parseLong(lastRefill1);
        long timestampValue2 = Long.parseLong(lastRefill2);
        assertThat(timestampValue2).isGreaterThanOrEqualTo(timestampValue1);
        System.out.printf("✅ Timestamp updated: %d -> %d\n", timestampValue1, timestampValue2);
        
        // Verify response remaining matches Redis tokens (converted from microtokens)
        long expectedRemaining = tokensValue2 / 1_000_000;
        assertThat(response2.getRemaining()).isEqualTo(expectedRemaining);
        System.out.printf("✅ Response remaining (%d) matches Redis tokens (%d microtokens = %d tokens)\n", 
            response2.getRemaining(), tokensValue2, expectedRemaining);
    }

    @Test
    void verifyDifferentKeysUseDifferentRedisKeys() {
        System.out.println("=== Verifying Different Keys Use Different Redis Keys ===");
        
        RateLimitRequest request1 = createRequest("user-123", 3L, "60s", 1);
        RateLimitRequest request2 = createRequest("user-456", 3L, "60s", 1);
        
        String redisKey1 = "rl:user-123:tb";
        String redisKey2 = "rl:user-456:tb";
        
        // Execute requests
        RateLimitResponse response1 = strategy.execute(request1);
        RateLimitResponse response2 = strategy.execute(request2);
        
        System.out.printf("User-123: allowed=%s, remaining=%d\n", response1.isAllowed(), response1.getRemaining());
        System.out.printf("User-456: allowed=%s, remaining=%d\n", response2.isAllowed(), response2.getRemaining());
        
        // Verify both keys exist
        assertThat(redisTemplate.hasKey(redisKey1)).isTrue();
        assertThat(redisTemplate.hasKey(redisKey2)).isTrue();
        System.out.println("✅ Both Redis keys exist");
        
        // Verify they have different states
        Map<Object, Object> state1 = redisTemplate.opsForHash().entries(redisKey1);
        Map<Object, Object> state2 = redisTemplate.opsForHash().entries(redisKey2);
        
        String tokens1 = (String) state1.get("tokens");
        String tokens2 = (String) state2.get("tokens");
        
        System.out.printf("User-123 tokens: %s\n", tokens1);
        System.out.printf("User-456 tokens: %s\n", tokens2);
        
        // Both should have same initial state (2 tokens remaining after 1 consumed from 3)
        assertThat(tokens1).isEqualTo(tokens2);
        System.out.println("✅ Independent keys have independent state");
    }

    @Test
    void verifyTokenExhaustion() {
        System.out.println("=== Verifying Token Exhaustion Behavior ===");
        
        String testKey = "exhaustion-test";
        RateLimitRequest request = createRequest(testKey, 2L, "60s", 1);
        String redisKey = "rl:" + testKey + ":tb";
        
        // Consume all tokens
        RateLimitResponse r1 = strategy.execute(request);
        RateLimitResponse r2 = strategy.execute(request);
        RateLimitResponse r3 = strategy.execute(request); // Should be denied
        
        System.out.printf("Request 1: allowed=%s, remaining=%d\n", r1.isAllowed(), r1.getRemaining());
        System.out.printf("Request 2: allowed=%s, remaining=%d\n", r2.isAllowed(), r2.getRemaining());
        System.out.printf("Request 3: allowed=%s, remaining=%d\n", r3.isAllowed(), r3.getRemaining());
        
        // Verify final state
        assertThat(r1.isAllowed()).isTrue();
        assertThat(r1.getRemaining()).isEqualTo(1);
        
        assertThat(r2.isAllowed()).isTrue();
        assertThat(r2.getRemaining()).isEqualTo(0);
        
        assertThat(r3.isAllowed()).isFalse();
        assertThat(r3.getRemaining()).isEqualTo(0);
        
        // Verify Redis state shows zero tokens
        Map<Object, Object> finalState = redisTemplate.opsForHash().entries(redisKey);
        String finalTokens = (String) finalState.get("tokens");
        long finalTokensValue = Long.parseLong(finalTokens);
        
        System.out.printf("Final Redis tokens: %s (%d microtokens)\n", finalTokens, finalTokensValue);
        assertThat(finalTokensValue).isEqualTo(0);
        System.out.println("✅ Redis shows zero tokens after exhaustion");
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