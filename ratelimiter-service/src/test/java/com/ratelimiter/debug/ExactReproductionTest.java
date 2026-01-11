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

/**
 * Test to reproduce the exact issue described:
 * - limit=5, cost=1 → remaining always 4 on rapid requests
 * - limit=3, cost=1 → remaining always 2 on rapid requests
 */
@SpringBootTest
@Testcontainers
class ExactReproductionTest {

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
    void reproduceIssue_limit5_cost1_rapidRequests() {
        System.out.println("=== Reproducing: limit=5, cost=1 → remaining always 4? ===");
        
        RateLimitRequest request = createRequest("user123", 5L, "60s", 1);

        // Make 10 rapid requests in a tight loop
        for (int i = 1; i <= 10; i++) {
            long startTime = System.nanoTime();
            RateLimitResponse response = strategy.execute(request);
            long endTime = System.nanoTime();
            long durationMicros = (endTime - startTime) / 1000;
            
            System.out.printf("Request %d: allowed=%s, remaining=%d (took %d μs)%n", 
                i, response.isAllowed(), response.getRemaining(), durationMicros);
            
            // If we see the issue (remaining always 4), break early
            if (i > 1 && response.isAllowed() && response.getRemaining() == 4) {
                System.out.println("*** ISSUE REPRODUCED: remaining stuck at 4! ***");
                break;
            }
        }
    }

    @Test
    void reproduceIssue_limit3_cost1_rapidRequests() {
        System.out.println("=== Reproducing: limit=3, cost=1 → remaining always 2? ===");
        
        RateLimitRequest request = createRequest("user456", 3L, "60s", 1);

        // Make 8 rapid requests in a tight loop
        for (int i = 1; i <= 8; i++) {
            long startTime = System.nanoTime();
            RateLimitResponse response = strategy.execute(request);
            long endTime = System.nanoTime();
            long durationMicros = (endTime - startTime) / 1000;
            
            System.out.printf("Request %d: allowed=%s, remaining=%d (took %d μs)%n", 
                i, response.isAllowed(), response.getRemaining(), durationMicros);
            
            // If we see the issue (remaining always 2), break early
            if (i > 1 && response.isAllowed() && response.getRemaining() == 2) {
                System.out.println("*** ISSUE REPRODUCED: remaining stuck at 2! ***");
                break;
            }
        }
    }

    @Test
    void checkRedisDirectly_limit5() {
        System.out.println("=== Checking Redis state directly for limit=5 ===");
        
        RateLimitRequest request = createRequest("direct-test", 5L, "60s", 1);
        String redisKey = "rl:direct-test:tb";

        // Make 3 requests and check Redis state after each
        for (int i = 1; i <= 3; i++) {
            System.out.printf("\n--- Before request %d ---\n", i);
            printRedisState(redisKey);
            
            RateLimitResponse response = strategy.execute(request);
            System.out.printf("Response %d: allowed=%s, remaining=%d\n", i, response.isAllowed(), response.getRemaining());
            
            System.out.printf("--- After request %d ---\n", i);
            printRedisState(redisKey);
        }
    }

    @Test
    void testWithZeroDelay() {
        System.out.println("=== Testing with absolutely zero delay ===");
        
        RateLimitRequest request = createRequest("zero-delay", 5L, "60s", 1);

        // Execute requests back-to-back with no delay
        RateLimitResponse r1 = strategy.execute(request);
        RateLimitResponse r2 = strategy.execute(request);
        RateLimitResponse r3 = strategy.execute(request);
        RateLimitResponse r4 = strategy.execute(request);
        RateLimitResponse r5 = strategy.execute(request);
        RateLimitResponse r6 = strategy.execute(request);

        System.out.printf("Request 1: allowed=%s, remaining=%d\n", r1.isAllowed(), r1.getRemaining());
        System.out.printf("Request 2: allowed=%s, remaining=%d\n", r2.isAllowed(), r2.getRemaining());
        System.out.printf("Request 3: allowed=%s, remaining=%d\n", r3.isAllowed(), r3.getRemaining());
        System.out.printf("Request 4: allowed=%s, remaining=%d\n", r4.isAllowed(), r4.getRemaining());
        System.out.printf("Request 5: allowed=%s, remaining=%d\n", r5.isAllowed(), r5.getRemaining());
        System.out.printf("Request 6: allowed=%s, remaining=%d\n", r6.isAllowed(), r6.getRemaining());
    }

    private void printRedisState(String key) {
        try {
            Object tokensObj = redisTemplate.opsForHash().get(key, "tokens");
            Object lastRefillObj = redisTemplate.opsForHash().get(key, "last_refill_ms");
            
            if (tokensObj != null && lastRefillObj != null) {
                String tokens = tokensObj.toString();
                String lastRefill = lastRefillObj.toString();
                System.out.printf("Redis key=%s: tokens=%s, last_refill_ms=%s\n", key, tokens, lastRefill);
            } else {
                System.out.printf("Redis key=%s: not found\n", key);
            }
        } catch (Exception e) {
            System.out.printf("Redis key=%s: error reading: %s\n", key, e.getMessage());
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