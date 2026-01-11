package com.ratelimiter.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for Sliding Window Log algorithm via API endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SlidingWindowLogIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testSlidingWindowLogViaAPI() throws Exception {
        System.out.println("=== Testing Sliding Window Log via API ===");
        
        // Test 1: First request should be allowed
        RateLimitRequest request = createRequest("swl-test-key", "sliding_window_log", 3L, "60s", 1);
        
        MvcResult result1 = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        RateLimitResponse response1 = objectMapper.readValue(result1.getResponse().getContentAsString(), RateLimitResponse.class);
        System.out.printf("Request 1: allowed=%s, remaining=%d\n", response1.isAllowed(), response1.getRemaining());
        
        assertThat(response1.isAllowed()).isTrue();
        assertThat(response1.getRemaining()).isEqualTo(2);

        // Test 2: Second request should be allowed
        MvcResult result2 = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        RateLimitResponse response2 = objectMapper.readValue(result2.getResponse().getContentAsString(), RateLimitResponse.class);
        System.out.printf("Request 2: allowed=%s, remaining=%d\n", response2.isAllowed(), response2.getRemaining());
        
        assertThat(response2.isAllowed()).isTrue();
        assertThat(response2.getRemaining()).isEqualTo(1);

        // Test 3: Third request should be allowed
        MvcResult result3 = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        RateLimitResponse response3 = objectMapper.readValue(result3.getResponse().getContentAsString(), RateLimitResponse.class);
        System.out.printf("Request 3: allowed=%s, remaining=%d\n", response3.isAllowed(), response3.getRemaining());
        
        assertThat(response3.isAllowed()).isTrue();
        assertThat(response3.getRemaining()).isEqualTo(0);

        // Test 4: Fourth request should be denied (limit reached)
        MvcResult result4 = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        RateLimitResponse response4 = objectMapper.readValue(result4.getResponse().getContentAsString(), RateLimitResponse.class);
        System.out.printf("Request 4: allowed=%s, remaining=%d\n", response4.isAllowed(), response4.getRemaining());
        
        assertThat(response4.isAllowed()).isFalse();
        assertThat(response4.getRemaining()).isEqualTo(0);
        
        System.out.println("✅ Sliding Window Log API test completed successfully");
    }

    @Test
    void testSlidingWindowLogWithCost() throws Exception {
        System.out.println("=== Testing Sliding Window Log with Cost ===");
        
        // Test with cost=2
        RateLimitRequest request = createRequest("swl-cost-key", "sliding_window_log", 5L, "60s", 2);
        
        MvcResult result1 = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        RateLimitResponse response1 = objectMapper.readValue(result1.getResponse().getContentAsString(), RateLimitResponse.class);
        System.out.printf("Request with cost=2: allowed=%s, remaining=%d\n", response1.isAllowed(), response1.getRemaining());
        
        assertThat(response1.isAllowed()).isTrue();
        assertThat(response1.getRemaining()).isEqualTo(3); // 5 - 2 = 3

        // Second request with cost=2
        MvcResult result2 = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        RateLimitResponse response2 = objectMapper.readValue(result2.getResponse().getContentAsString(), RateLimitResponse.class);
        System.out.printf("Second request with cost=2: allowed=%s, remaining=%d\n", response2.isAllowed(), response2.getRemaining());
        
        assertThat(response2.isAllowed()).isTrue();
        assertThat(response2.getRemaining()).isEqualTo(1); // 3 - 2 = 1

        // Third request with cost=2 should be denied (only 1 remaining)
        MvcResult result3 = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        RateLimitResponse response3 = objectMapper.readValue(result3.getResponse().getContentAsString(), RateLimitResponse.class);
        System.out.printf("Third request with cost=2: allowed=%s, remaining=%d\n", response3.isAllowed(), response3.getRemaining());
        
        assertThat(response3.isAllowed()).isFalse();
        assertThat(response3.getRemaining()).isEqualTo(1); // Still 1 remaining
        
        System.out.println("✅ Sliding Window Log cost test completed successfully");
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