package com.ratelimiter.strategy;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.infrastructure.LuaScriptExecutor;
import com.ratelimiter.infrastructure.RedisTimeProvider;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for SlidingWindowLogRateLimiterStrategy using Testcontainers.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class SlidingWindowLogRateLimiterStrategyTest {

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
    private LuaScriptExecutor scriptExecutor;

    @Autowired
    private RedisTimeProvider timeProvider;

    private SlidingWindowLogRateLimiterStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SlidingWindowLogRateLimiterStrategy(scriptExecutor, timeProvider);
        // Clear Redis before each test
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void getAlgorithm_shouldReturnSlidingWindowLog() {
        // When/Then
        assertThat(strategy.getAlgorithm()).isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW_LOG);
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
        // Given - 2 requests per second
        RateLimitRequest request = createRequest("test-key", 2L, "1s", 1);

        // When - Exhaust the limit
        strategy.execute(request);
        strategy.execute(request);
        
        // Verify limit is reached
        RateLimitResponse exhaustedResponse = strategy.execute(request);
        assertThat(exhaustedResponse.isAllowed()).isFalse();

        // Wait for window to slide (1.1 seconds to account for timing precision)
        Thread.sleep(1100);

        // Then - Should allow requests again as old entries expire
        RateLimitResponse afterSlide = strategy.execute(request);
        assertThat(afterSlide.isAllowed()).isTrue();
        assertThat(afterSlide.getRemaining()).isGreaterThan(0);
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
        assertThat(response.getRemaining()).isEqualTo(5); // Limit remains available
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

        // Then - Exactly 10 should be allowed, 10 should be denied
        long allowedCount = responses.stream().mapToLong(r -> r.isAllowed() ? 1 : 0).sum();
        long deniedCount = responses.stream().mapToLong(r -> r.isAllowed() ? 0 : 1).sum();

        assertThat(allowedCount).isEqualTo(10);
        assertThat(deniedCount).isEqualTo(10);
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
    void execute_perfectAccuracyWithSlidingWindow() throws InterruptedException {
        // Given - 3 requests per 2 seconds
        RateLimitRequest request = createRequest("accuracy-key", 3L, "2s", 1);

        // When - Make 3 requests at t=0
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            RateLimitResponse response = strategy.execute(request);
            assertThat(response.isAllowed()).isTrue();
        }

        // Should be denied immediately after
        RateLimitResponse denied1 = strategy.execute(request);
        assertThat(denied1.isAllowed()).isFalse();

        // Wait 1 second (half the window)
        Thread.sleep(1000);
        
        // Should still be denied (all 3 requests still in 2s window)
        RateLimitResponse denied2 = strategy.execute(request);
        assertThat(denied2.isAllowed()).isFalse();

        // Wait another 1.1 seconds (total 2.1s from start)
        Thread.sleep(1100);
        
        // Now should be allowed as original requests have expired
        RateLimitResponse allowed = strategy.execute(request);
        assertThat(allowed.isAllowed()).isTrue();
    }

    @Test
    void validateRequest_nullRequest_shouldThrow() {
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Request cannot be null");
    }

    @Test
    void validateRequest_nullKey_shouldThrow() {
        // Given
        RateLimitRequest request = createRequest(null, 10L, "60s", 1);

        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Key cannot be null or empty");
    }

    @Test
    void validateRequest_emptyKey_shouldThrow() {
        // Given
        RateLimitRequest request = createRequest("", 10L, "60s", 1);

        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Key cannot be null or empty");
    }

    @Test
    void validateRequest_nullLimit_shouldThrow() {
        // Given
        RateLimitRequest request = createRequest("key", null, "60s", 1);

        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Limit must be positive");
    }

    @Test
    void validateRequest_zeroLimit_shouldThrow() {
        // Given
        RateLimitRequest request = createRequest("key", 0L, "60s", 1);

        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Limit must be positive");
    }

    @Test
    void validateRequest_zeroCost_shouldThrow() {
        // Given
        RateLimitRequest request = createRequest("key", 10L, "60s", 0);

        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cost must be positive");
    }

    @Test
    void validateRequest_nullWindow_shouldThrow() {
        // Given
        RateLimitRequest request = createRequest("key", 10L, null, 1);

        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Window cannot be null or empty");
    }

    @Test
    void validateRequest_invalidWindowFormat_shouldThrow() {
        // Given
        RateLimitRequest request = createRequest("key", 10L, "invalid", 1);

        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid window format");
    }

    @Test
    void validateRequest_unsupportedWindowUnit_shouldThrow() {
        // Given
        RateLimitRequest request = createRequest("key", 10L, "60x", 1);

        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid window format");
    }

    @Test
    void validateRequest_validRequest_shouldNotThrow() {
        // Given
        RateLimitRequest request = createRequest("valid-key", 100L, "5m", 2);

        // When/Then - Should not throw
        strategy.validateRequest(request);
    }

    private RateLimitRequest createRequest(String key, Long limit, String window, int cost) {
        RateLimitRequest request = new RateLimitRequest();
        request.setKey(key);
        request.setAlgorithm("sliding_window_log");
        request.setLimit(limit);
        request.setWindow(window);
        request.setCost(cost);
        return request;
    }
}