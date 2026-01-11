package com.ratelimiter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.dto.RateLimitRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for RateLimitController with Token Bucket algorithm.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class RateLimitControllerTokenBucketTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        // Clear Redis before each test
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void checkRateLimit_tokenBucket_allowedRequest() throws Exception {
        // Given
        RateLimitRequest request = createTokenBucketRequest("controller-key", 5L, "60s", 1);

        // When/Then
        mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.remaining").value(4))
                .andExpect(jsonPath("$.resetTime").exists());
    }

    @Test
    void checkRateLimit_tokenBucket_deniedRequest() throws Exception {
        // Given
        RateLimitRequest request = createTokenBucketRequest("controller-key-2", 1L, "60s", 1);

        // When - First request should be allowed
        mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.remaining").value(0));

        // Then - Second request should be denied
        mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.resetTime").exists());
    }

    @Test
    void checkRateLimit_invalidAlgorithm_shouldReturn400() throws Exception {
        // Given
        RateLimitRequest request = createRequest("key", "invalid_algorithm", 10L, "60s", 1);

        // When/Then
        mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkRateLimit_missingKey_shouldReturn400() throws Exception {
        // Given
        RateLimitRequest request = createTokenBucketRequest(null, 10L, "60s", 1);

        // When/Then
        mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
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