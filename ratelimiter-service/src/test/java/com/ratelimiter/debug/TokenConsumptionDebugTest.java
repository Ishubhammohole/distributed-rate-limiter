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

/**
 * Debug test to reproduce the token consumption issue.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class TokenConsumptionDebugTest {

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
    void debugTokenConsumption_limit5_cost1() {
        // Given
        RateLimitRequest request = createRequest("debug-key-1", 5L, "60s", 1);

        System.out.println("=== Testing limit=5, cost=1, window=60s ===");
        
        // When - Make rapid requests
        for (int i = 1; i <= 7; i++) {
            RateLimitResponse response = strategy.execute(request);
            System.out.printf("Request %d: allowed=%s, remaining=%d, resetTime=%s%n", 
                i, response.isAllowed(), response.getRemaining(), response.getResetTime());
        }
    }

    @Test
    void debugTokenConsumption_limit3_cost1() {
        // Given
        RateLimitRequest request = createRequest("debug-key-2", 3L, "60s", 1);

        System.out.println("=== Testing limit=3, cost=1, window=60s ===");
        
        // When - Make rapid requests
        for (int i = 1; i <= 5; i++) {
            RateLimitResponse response = strategy.execute(request);
            System.out.printf("Request %d: allowed=%s, remaining=%d, resetTime=%s%n", 
                i, response.isAllowed(), response.getRemaining(), response.getResetTime());
        }
    }

    @Test
    void debugRedisState() {
        // Given
        RateLimitRequest request = createRequest("debug-key-3", 5L, "60s", 1);
        String redisKey = "rl:debug-key-3:tb";

        System.out.println("=== Debugging Redis State ===");
        
        // Check initial state
        System.out.println("Initial Redis state:");
        printRedisState(redisKey);
        
        // Make first request
        System.out.println("\nAfter first request:");
        RateLimitResponse response1 = strategy.execute(request);
        System.out.printf("Response: allowed=%s, remaining=%d%n", response1.isAllowed(), response1.getRemaining());
        printRedisState(redisKey);
        
        // Make second request
        System.out.println("\nAfter second request:");
        RateLimitResponse response2 = strategy.execute(request);
        System.out.printf("Response: allowed=%s, remaining=%d%n", response2.isAllowed(), response2.getRemaining());
        printRedisState(redisKey);
        
        // Make third request
        System.out.println("\nAfter third request:");
        RateLimitResponse response3 = strategy.execute(request);
        System.out.printf("Response: allowed=%s, remaining=%d%n", response3.isAllowed(), response3.getRemaining());
        printRedisState(redisKey);
    }

    private void printRedisState(String key) {
        try {
            String tokens = redisTemplate.opsForHash().get(key, "tokens").toString();
            String lastRefill = redisTemplate.opsForHash().get(key, "last_refill_ms").toString();
            System.out.printf("Redis key=%s: tokens=%s, last_refill_ms=%s%n", key, tokens, lastRefill);
        } catch (Exception e) {
            System.out.printf("Redis key=%s: not found or error: %s%n", key, e.getMessage());
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