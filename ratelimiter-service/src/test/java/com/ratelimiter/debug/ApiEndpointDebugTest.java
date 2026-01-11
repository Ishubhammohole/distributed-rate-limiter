package com.ratelimiter.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Debug test to reproduce the API endpoint issue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiEndpointDebugTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Clear Redis before each test
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void debugApiEndpoint_limit5_cost1() throws Exception {
        System.out.println("=== Testing API endpoint: limit=5, cost=1 ===");
        
        RateLimitRequest request = createRequest("api-test-key-1", 5L, "60s", 1);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<RateLimitRequest> entity = new HttpEntity<>(request, headers);

        // Make rapid API calls
        for (int i = 1; i <= 7; i++) {
            ResponseEntity<RateLimitResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/ratelimit/check",
                entity,
                RateLimitResponse.class
            );

            RateLimitResponse rateLimitResponse = response.getBody();
            
            System.out.printf("API Request %d: allowed=%s, remaining=%d, resetTime=%s%n", 
                i, rateLimitResponse.isAllowed(), rateLimitResponse.getRemaining(), rateLimitResponse.getResetTime());
        }
    }

    @Test
    void debugApiEndpoint_limit3_cost1() throws Exception {
        System.out.println("=== Testing API endpoint: limit=3, cost=1 ===");
        
        RateLimitRequest request = createRequest("api-test-key-2", 3L, "60s", 1);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<RateLimitRequest> entity = new HttpEntity<>(request, headers);

        // Make rapid API calls
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<RateLimitResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/ratelimit/check",
                entity,
                RateLimitResponse.class
            );

            RateLimitResponse rateLimitResponse = response.getBody();
            
            System.out.printf("API Request %d: allowed=%s, remaining=%d, resetTime=%s%n", 
                i, rateLimitResponse.isAllowed(), rateLimitResponse.getRemaining(), rateLimitResponse.getResetTime());
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