package com.ratelimiter.repro;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.strategy.TokenBucketRateLimiterStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * Reproduction test for time unit consistency and refill rate calculations.
 * 
 * @Disabled by default to prevent CI execution.
 * To run manually: mvn test -Dtest=TimeConsistencyReproTest
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("repro")
@Disabled("Manual reproduction test - run with: mvn test -Dtest=TimeConsistencyReproTest")
class TimeConsistencyReproTest {

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
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void reproduceRefillRateIssue() {
        System.out.println("=== Reproducing Refill Rate Issue ===");
        
        // Use 60s window - creates very small refill rate
        RateLimitRequest request = createRequest("refill-demo", 5L, "60s", 1);
        
        // Calculate expected refill rate
        long windowMs = 60 * 1000; // 60 seconds = 60,000 ms
        double refillRatePerMs = (double) 5 / windowMs;
        long refillRateMicroPerMs = Math.round(refillRatePerMs * 1_000_000);
        
        System.out.printf("Window: 60s = %d ms%n", windowMs);
        System.out.printf("Refill rate: %.10f tokens/ms%n", refillRatePerMs);
        System.out.printf("Refill rate: %d microtokens/ms%n", refillRateMicroPerMs);
        System.out.printf("Even 5ms gap = %d microtokens refilled%n", 5 * refillRateMicroPerMs);
        
        // Exhaust tokens rapidly
        long startTime = System.currentTimeMillis();
        RateLimitResponse r1 = strategy.execute(request);
        long time1 = System.currentTimeMillis();
        RateLimitResponse r2 = strategy.execute(request);
        long time2 = System.currentTimeMillis();
        RateLimitResponse r3 = strategy.execute(request);
        long time3 = System.currentTimeMillis();
        
        System.out.printf("Request 1: %dms, remaining=%d%n", time1 - startTime, r1.getRemaining());
        System.out.printf("Request 2: %dms, remaining=%d (gap: %dms)%n", 
            time2 - startTime, r2.getRemaining(), time2 - time1);
        System.out.printf("Request 3: %dms, remaining=%d (gap: %dms)%n", 
            time3 - startTime, r3.getRemaining(), time3 - time2);
        
        // Show that even small gaps cause refill
        long gap1 = time2 - time1;
        long gap2 = time3 - time2;
        long expectedRefill1 = gap1 * refillRateMicroPerMs;
        long expectedRefill2 = gap2 * refillRateMicroPerMs;
        
        System.out.printf("Expected refill from gap1 (%dms): %d microtokens%n", gap1, expectedRefill1);
        System.out.printf("Expected refill from gap2 (%dms): %d microtokens%n", gap2, expectedRefill2);
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