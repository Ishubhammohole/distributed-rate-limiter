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
 * Reproduction test for Lua script state debugging.
 * 
 * @Disabled by default to prevent CI execution.
 * To run manually: mvn test -Dtest=LuaScriptStateReproTest
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("repro")
@Disabled("Manual reproduction test - run with: mvn test -Dtest=LuaScriptStateReproTest")
@SuppressWarnings("deprecation") // Testcontainers GenericContainer constructor - acceptable for manual debugging tools
class LuaScriptStateReproTest {

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
    void debugLuaScriptRedisState() {
        System.out.println("=== Debugging Lua Script Redis State ===");
        
        RateLimitRequest request = createRequest("lua-debug", 5L, "60s", 1);
        String keyPrefix = "rate_limit:sliding_window_counter:lua-debug:";
        
        System.out.println("Initial Redis state:");
        printRedisKeys(keyPrefix);
        
        // Make first request
        System.out.println("\nAfter first request:");
        RateLimitResponse response1 = strategy.execute(request);
        System.out.printf("Response: allowed=%s, remaining=%d%n", 
            response1.isAllowed(), response1.getRemaining());
        printRedisKeys(keyPrefix);
        
        // Make second request
        System.out.println("\nAfter second request:");
        RateLimitResponse response2 = strategy.execute(request);
        System.out.printf("Response: allowed=%s, remaining=%d%n", 
            response2.isAllowed(), response2.getRemaining());
        printRedisKeys(keyPrefix);
    }

    private void printRedisKeys(String keyPrefix) {
        try {
            var keys = redisTemplate.keys(keyPrefix + "*");
            if (keys == null || keys.isEmpty()) {
                System.out.println("No Redis keys found with prefix: " + keyPrefix);
                return;
            }
            
            for (String key : keys) {
                String value = redisTemplate.opsForValue().get(key);
                Long ttl = redisTemplate.getExpire(key);
                System.out.printf("Key: %s = %s (TTL: %ds)%n", key, value, ttl);
            }
        } catch (Exception e) {
            System.out.printf("Error reading Redis keys: %s%n", e.getMessage());
        }
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