package com.ratelimiter.service;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for RateLimiterService with Token Bucket algorithm.
 */
@SpringBootTest
@Testcontainers
class RateLimiterServiceTokenBucketTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void setUp() {
        // Clear Redis before each test
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void checkRateLimit_tokenBucket_allowsThenDenies() {
        // Given
        RateLimitRequest request = createTokenBucketRequest("service-test-key", 3L, "60s", 1);

        // When - Make requests up to limit
        RateLimitResponse response1 = rateLimiterService.checkRateLimit(request);
        RateLimitResponse response2 = rateLimiterService.checkRateLimit(request);
        RateLimitResponse response3 = rateLimiterService.checkRateLimit(request);
        RateLimitResponse response4 = rateLimiterService.checkRateLimit(request);

        // Then
        assertThat(response1.isAllowed()).isTrue();
        assertThat(response1.getRemaining()).isEqualTo(2L);

        assertThat(response2.isAllowed()).isTrue();
        assertThat(response2.getRemaining()).isEqualTo(1L);

        assertThat(response3.isAllowed()).isTrue();
        assertThat(response3.getRemaining()).isEqualTo(0L);

        assertThat(response4.isAllowed()).isFalse();
        assertThat(response4.getRemaining()).isEqualTo(0L);
    }

    @Test
    void checkRateLimit_unsupportedAlgorithm_shouldThrow() {
        // Given
        RateLimitRequest request = createRequest("test-key", "unsupported_algorithm", 10L, "60s", 1);

        // When/Then
        assertThatThrownBy(() -> rateLimiterService.checkRateLimit(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid algorithm");
    }

    @Test
    void checkRateLimit_invalidAlgorithmName_shouldThrow() {
        // Given
        RateLimitRequest request = createRequest("test-key", "invalid_name", 10L, "60s", 1);

        // When/Then
        assertThatThrownBy(() -> rateLimiterService.checkRateLimit(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid algorithm");
    }

    @Test
    void checkRateLimit_differentKeys_areIndependent() {
        // Given
        RateLimitRequest request1 = createTokenBucketRequest("key1", 2L, "60s", 1);
        RateLimitRequest request2 = createTokenBucketRequest("key2", 2L, "60s", 1);

        // When - Exhaust key1
        rateLimiterService.checkRateLimit(request1);
        rateLimiterService.checkRateLimit(request1);
        RateLimitResponse key1Denied = rateLimiterService.checkRateLimit(request1);

        // Then - key1 denied, key2 still works
        assertThat(key1Denied.isAllowed()).isFalse();

        RateLimitResponse key2Response = rateLimiterService.checkRateLimit(request2);
        assertThat(key2Response.isAllowed()).isTrue();
        assertThat(key2Response.getRemaining()).isEqualTo(1L);
    }

    private RateLimitRequest createTokenBucketRequest(String key, Long limit, String window, int cost) {
        return createRequest(key, "token_bucket", limit, window, cost);
    }

    private RateLimitRequest createRequest(String key, String algorithm, Long limit, String window, int cost) {
        RateLimitRequest request = new RateLimitRequest();
        request.setKey(key);
        request.setAlgorithm(algorithm);
        request.setLimit(limit);
        request.setWindow(window);
        request.setCost(cost);
        return request;
    }
}