package com.ratelimiter.integration;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.strategy.SlidingWindowCounterRateLimiterStrategy;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SlidingWindowCounterRateLimiterStrategy using Testcontainers.
 * Tests the complete algorithm behavior with real Redis operations.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class SlidingWindowCounterIntegrationTest {

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
        // Clear Redis before each test
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void getAlgorithm_shouldReturnSlidingWindowCounter() {
        // When/Then
        assertThat(strategy.getAlgorithm()).isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW_COUNTER);
    }

    @Test
    void execute_allowsUpToLimitImmediately() {
        // Given
        RateLimitRequest request = createRequest("test-key", 5L, "60s", 1);

        // When - Make 5 requests (up to limit)
        List<RateLimitResponse> responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            responses.add(strategy.execute(request));
        }

        // Then - All should be allowed with decreasing remaining count
        for (int i = 0; i < 5; i++) {
            RateLimitResponse response = responses.get(i);
            assertThat(response.isAllowed()).isTrue();
            assertThat(response.getRemaining()).isEqualTo(4 - i); // 4, 3, 2, 1, 0
        }
    }

    @Test
    void execute_deniesOnceExhausted() {
        // Given
        RateLimitRequest request = createRequest("test-key", 3L, "60s", 1);

        // When - Exhaust the limit
        for (int i = 0; i < 3; i++) {
            RateLimitResponse response = strategy.execute(request);
            assertThat(response.isAllowed()).isTrue();
        }

        // Then - Next request should be denied
        RateLimitResponse deniedResponse = strategy.execute(request);
        assertThat(deniedResponse.isAllowed()).isFalse();
        assertThat(deniedResponse.getRemaining()).isEqualTo(0);
    }

    @Test
    void execute_allowsRequestsAfterWindowSlides() throws InterruptedException {
        // Given - 2 requests per 2 seconds
        RateLimitRequest request = createRequest("test-key", 2L, "2s", 1);

        // When - Exhaust the limit
        strategy.execute(request);
        strategy.execute(request);
        
        // Verify limit is exhausted
        RateLimitResponse exhaustedResponse = strategy.execute(request);
        assertThat(exhaustedResponse.isAllowed()).isFalse();

        // Wait for window to slide (2.1 seconds to account for timing precision)
        Thread.sleep(2100);

        // Then - Should allow requests again as window has slid
        RateLimitResponse afterSlide = strategy.execute(request);
        assertThat(afterSlide.isAllowed()).isTrue();
        assertThat(afterSlide.getRemaining()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_costGreaterThanOneWorks() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 3);

        // When - Make request with cost 3
        RateLimitResponse response1 = strategy.execute(request);
        RateLimitResponse response2 = strategy.execute(request);

        // Then
        assertThat(response1.isAllowed()).isTrue();
        assertThat(response1.getRemaining()).isEqualTo(7); // 10 - 3 = 7

        assertThat(response2.isAllowed()).isTrue();
        assertThat(response2.getRemaining()).isEqualTo(4); // 7 - 3 = 4
    }

    @Test
    void execute_costExceedsLimit_shouldDeny() {
        // Given
        RateLimitRequest request = createRequest("test-key", 5L, "60s", 10);

        // When
        RateLimitResponse response = strategy.execute(request);

        // Then
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getRemaining()).isEqualTo(5); // Limit remains unchanged
    }

    @Test
    void execute_multipleKeysAreIndependent() {
        // Given
        RateLimitRequest request1 = createRequest("key1", 2L, "60s", 1);
        RateLimitRequest request2 = createRequest("key2", 2L, "60s", 1);

        // When - Exhaust key1
        strategy.execute(request1);
        strategy.execute(request1);
        RateLimitResponse key1Denied = strategy.execute(request1);

        // Then - key1 should be denied, key2 should still work
        assertThat(key1Denied.isAllowed()).isFalse();

        RateLimitResponse key2Response = strategy.execute(request2);
        assertThat(key2Response.isAllowed()).isTrue();
        assertThat(key2Response.getRemaining()).isEqualTo(1);
    }

    @Test
    void execute_concurrencySanityTest() throws InterruptedException {
        // Given - 10 request limit
        RateLimitRequest request = createRequest("concurrent-key", 10L, "60s", 1);
        int numThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        // When - 20 parallel requests for 10 limit
        List<CompletableFuture<RateLimitResponse>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> strategy.execute(request), executor));
        }

        // Wait for all to complete
        List<RateLimitResponse> responses = new ArrayList<>();
        for (CompletableFuture<RateLimitResponse> future : futures) {
            try {
                responses.add(future.get(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new RuntimeException("Failed to get response from future", e);
            }
        }

        executor.shutdown();

        // Then - Should have approximately 10 allowed, 10 denied (allowing for some approximation)
        long allowedCount = responses.stream().mapToLong(r -> r.isAllowed() ? 1 : 0).sum();
        long deniedCount = responses.stream().mapToLong(r -> r.isAllowed() ? 0 : 1).sum();

        // Sliding window counter is approximate, so allow some tolerance
        assertThat(allowedCount).isBetween(8L, 12L); // Allow 20% tolerance
        assertThat(deniedCount).isBetween(8L, 12L);
        assertThat(allowedCount + deniedCount).isEqualTo(20);
    }

    @Test
    void execute_resetTimeIsReasonable() {
        // Given
        RateLimitRequest request = createRequest("reset-key", 1L, "10s", 1);

        // When - Exhaust limit
        strategy.execute(request);
        RateLimitResponse deniedResponse = strategy.execute(request);

        // Then - Reset time should be in the future but reasonable
        assertThat(deniedResponse.isAllowed()).isFalse();
        Instant now = Instant.now();
        Instant resetTime = deniedResponse.getResetTime();
        
        assertThat(resetTime).isAfter(now);
        assertThat(resetTime).isBefore(now.plusSeconds(15)); // Should be within window + buffer
    }

    @Test
    void execute_windowTransitionBehavior() throws InterruptedException {
        // Given - 3 requests per 3 seconds
        RateLimitRequest request = createRequest("transition-key", 3L, "3s", 1);

        // When - Make 2 requests in first window
        RateLimitResponse response1 = strategy.execute(request);
        RateLimitResponse response2 = strategy.execute(request);
        
        assertThat(response1.isAllowed()).isTrue();
        assertThat(response2.isAllowed()).isTrue();

        // Wait 1.5 seconds (halfway through window)
        Thread.sleep(1500);

        // Make 1 more request (should still be allowed due to window weighting)
        RateLimitResponse response3 = strategy.execute(request);
        assertThat(response3.isAllowed()).isTrue();

        // Wait another 2 seconds (total 3.5s, into next window)
        Thread.sleep(2000);

        // Then - Should allow more requests as we're in a new window
        RateLimitResponse response4 = strategy.execute(request);
        assertThat(response4.isAllowed()).isTrue();
    }

    @Test
    void execute_memoryEfficiency_usesConstantSpace() {
        // Given
        RateLimitRequest request = createRequest("memory-test", 100L, "60s", 1);

        // When - Make many requests to verify O(1) memory usage
        for (int i = 0; i < 50; i++) {
            strategy.execute(request);
        }

        // Then - Verify only 2 Redis keys exist for this rate limit key
        String currentKey = "rate_limit:sliding_window_counter:memory-test:current";
        String previousKey = "rate_limit:sliding_window_counter:memory-test:previous";
        
        assertThat(redisTemplate.hasKey(currentKey)).isTrue();
        // Previous key might not exist if we haven't crossed window boundary
        // but if it exists, it should have a value
        if (Boolean.TRUE.equals(redisTemplate.hasKey(previousKey))) {
            assertThat(redisTemplate.opsForValue().get(previousKey)).isNotNull();
        }
        
        // Verify no other keys exist for this rate limit
        String keyPattern = "rate_limit:sliding_window_counter:memory-test:*";
        var keys = redisTemplate.keys(keyPattern);
        assertThat(keys).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void execute_ttlBehavior_keysExpireCorrectly() throws InterruptedException {
        // Given
        RateLimitRequest request = createRequest("ttl-test", 5L, "2s", 1);

        // When - Make a request to create keys
        strategy.execute(request);

        // Then - Keys should have appropriate TTL (max(60, 2 * window size) = 60 seconds for small windows)
        String currentKey = "rate_limit:sliding_window_counter:ttl-test:current";
        Long ttl = redisTemplate.getExpire(currentKey);
        
        assertThat(ttl).isGreaterThan(0);
        assertThat(ttl).isLessThanOrEqualTo(60); // Should be <= max(60, 2 * window size)
        
        // For small windows, TTL should be the minimum of 60 seconds
        assertThat(ttl).isGreaterThan(50); // Should be close to 60, allowing for execution time
    }

    @Test
    void execute_approximationAccuracy_withinExpectedRange() throws InterruptedException {
        // Given - Test accuracy compared to exact sliding window
        RateLimitRequest request = createRequest("accuracy-test", 10L, "2s", 1);

        // When - Make requests at specific intervals to test approximation
        // Make 5 requests immediately
        for (int i = 0; i < 5; i++) {
            RateLimitResponse response = strategy.execute(request);
            assertThat(response.isAllowed()).isTrue();
        }

        // Wait 1 second (halfway through window)
        Thread.sleep(1000);

        // Make 5 more requests - some should be allowed due to approximation
        int allowedInSecondHalf = 0;
        for (int i = 0; i < 5; i++) {
            RateLimitResponse response = strategy.execute(request);
            if (response.isAllowed()) {
                allowedInSecondHalf++;
            }
        }

        // Then - Should allow some requests due to sliding window approximation
        // Exact sliding window would deny all, but counter approximation should allow some
        assertThat(allowedInSecondHalf).isGreaterThan(0);
        assertThat(allowedInSecondHalf).isLessThanOrEqualTo(5);
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