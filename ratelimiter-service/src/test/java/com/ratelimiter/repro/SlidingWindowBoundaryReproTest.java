package com.ratelimiter.repro;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.strategy.SlidingWindowCounterRateLimiterStrategy;
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
 * Reproduction test for sliding window boundary behavior.
 * 
 * @Disabled by default to prevent CI execution.
 * To run manually: mvn test -Dtest=SlidingWindowBoundaryReproTest
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("repro")
@Disabled("Manual reproduction test - run with: mvn test -Dtest=SlidingWindowBoundaryReproTest")
class SlidingWindowBoundaryReproTest {

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
    private SlidingWindowCounterRateLimiterStrategy strategy;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void reproduceWindowBoundaryIssue() {
        System.out.println("=== Reproducing Window Boundary Issue ===");
        
        RateLimitRequest request = createRequest("boundary-test", 3L, "10s", 1);
        
        // Fill window near boundary
        System.out.println("Filling window with 3 requests...");
        for (int i = 1; i <= 3; i++) {
            RateLimitResponse response = strategy.execute(request);
            System.out.printf("Request %d: allowed=%s, remaining=%d%n", 
                i, response.isAllowed(), response.getRemaining());
        }
        
        // 4th request should be denied
        RateLimitResponse response4 = strategy.execute(request);
        System.out.printf("Request 4: allowed=%s, remaining=%d%n", 
            response4.isAllowed(), response4.getRemaining());
        
        // Wait for window transition (simulate)
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Request in new window should be allowed
        RateLimitResponse response5 = strategy.execute(request);
        System.out.printf("Request 5 (new window): allowed=%s, remaining=%d%n", 
            response5.isAllowed(), response5.getRemaining());
    }

    private RateLimitRequest createRequest(String key, Long limit, String window, int cost) {
        RateLimitRequest request = new RateLimitRequest();
        request.setKey(key);
        request.setAlgorithm("sliding_window_counter");
        request.setLimit(limit);
        request.setWindow(window);
        request.setCost(cost);
        return request;
    }
}