package com.ratelimiter.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.dto.ErrorResponse;
import com.ratelimiter.dto.RateLimitRequest;
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
 * Test to verify standardized validation and error handling across all layers.
 * This test ensures consistent error messages and response formats.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ValidationStandardizationTest {

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
    void testNullKeyValidation() throws Exception {
        System.out.println("=== Testing Null Key Validation ===");
        
        RateLimitRequest request = createRequest(null, "token_bucket", 10L, "60s", 1);
        
        MvcResult result = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = objectMapper.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
        System.out.printf("Error: %s\n", error.getMessage());
        
        assertThat(error.getError()).isEqualTo("BAD_REQUEST");
        assertThat(error.getMessage()).isEqualTo("Validation failed");
        assertThat(error.getValidationErrors()).isNotEmpty();
        assertThat(error.getValidationErrors().get(0).getMessage()).contains("Key cannot be blank");
    }

    @Test
    void testEmptyKeyValidation() throws Exception {
        System.out.println("=== Testing Empty Key Validation ===");
        
        RateLimitRequest request = createRequest("", "token_bucket", 10L, "60s", 1);
        
        MvcResult result = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = objectMapper.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
        System.out.printf("Error: %s\n", error.getMessage());
        
        assertThat(error.getError()).isEqualTo("BAD_REQUEST");
        assertThat(error.getMessage()).isEqualTo("Validation failed");
        assertThat(error.getValidationErrors()).isNotEmpty();
        
        // Check that one of the validation errors contains the expected message
        boolean hasBlankKeyError = error.getValidationErrors().stream()
            .anyMatch(ve -> ve.getMessage().contains("Key cannot be blank"));
        assertThat(hasBlankKeyError).isTrue();
    }

    @Test
    void testInvalidAlgorithmValidation() throws Exception {
        System.out.println("=== Testing Invalid Algorithm Validation ===");
        
        RateLimitRequest request = createRequest("test-key", "invalid_algorithm", 10L, "60s", 1);
        
        MvcResult result = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = objectMapper.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
        System.out.printf("Error: %s\n", error.getMessage());
        
        assertThat(error.getError()).isEqualTo("BAD_REQUEST");
        assertThat(error.getMessage()).isEqualTo("Validation failed");
        assertThat(error.getValidationErrors()).isNotEmpty();
        assertThat(error.getValidationErrors().get(0).getMessage()).contains("Algorithm must be one of");
    }

    @Test
    void testUnsupportedAlgorithmValidation() throws Exception {
        System.out.println("=== Testing Unsupported Algorithm Validation ===");
        
        // sliding_window_counter is a valid algorithm but not implemented
        RateLimitRequest request = createRequest("test-key", "sliding_window_counter", 10L, "60s", 1);
        
        MvcResult result = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = objectMapper.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
        System.out.printf("Error: %s\n", error.getMessage());
        
        assertThat(error.getError()).isEqualTo("BAD_REQUEST");
        assertThat(error.getMessage()).contains("No strategy implementation found");
        assertThat(error.getMessage()).contains("SLIDING_WINDOW_COUNTER");
    }

    @Test
    void testInvalidLimitValidation() throws Exception {
        System.out.println("=== Testing Invalid Limit Validation ===");
        
        RateLimitRequest request = createRequest("test-key", "token_bucket", 0L, "60s", 1);
        
        MvcResult result = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = objectMapper.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
        System.out.printf("Error: %s\n", error.getMessage());
        
        assertThat(error.getError()).isEqualTo("BAD_REQUEST");
        assertThat(error.getMessage()).isEqualTo("Validation failed");
        assertThat(error.getValidationErrors()).isNotEmpty();
        assertThat(error.getValidationErrors().get(0).getMessage()).contains("Limit must be at least 1");
    }

    @Test
    void testInvalidCostValidation() throws Exception {
        System.out.println("=== Testing Invalid Cost Validation ===");
        
        RateLimitRequest request = createRequest("test-key", "token_bucket", 10L, "60s", 0);
        
        MvcResult result = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = objectMapper.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
        System.out.printf("Error: %s\n", error.getMessage());
        
        assertThat(error.getError()).isEqualTo("BAD_REQUEST");
        assertThat(error.getMessage()).isEqualTo("Validation failed");
        assertThat(error.getValidationErrors()).isNotEmpty();
        assertThat(error.getValidationErrors().get(0).getMessage()).contains("Cost must be at least 1");
    }

    @Test
    void testInvalidWindowFormatValidation() throws Exception {
        System.out.println("=== Testing Invalid Window Format Validation ===");
        
        RateLimitRequest request = createRequest("test-key", "token_bucket", 10L, "invalid", 1);
        
        MvcResult result = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = objectMapper.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
        System.out.printf("Error: %s\n", error.getMessage());
        
        assertThat(error.getError()).isEqualTo("BAD_REQUEST");
        assertThat(error.getMessage()).isEqualTo("Validation failed");
        assertThat(error.getValidationErrors()).isNotEmpty();
        assertThat(error.getValidationErrors().get(0).getMessage()).contains("Window must be in format");
    }

    @Test
    void testUnsupportedWindowUnitValidation() throws Exception {
        System.out.println("=== Testing Unsupported Window Unit Validation ===");
        
        RateLimitRequest request = createRequest("test-key", "token_bucket", 10L, "60x", 1);
        
        MvcResult result = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = objectMapper.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
        System.out.printf("Error: %s\n", error.getMessage());
        
        assertThat(error.getError()).isEqualTo("BAD_REQUEST");
        assertThat(error.getMessage()).isEqualTo("Validation failed");
        assertThat(error.getValidationErrors()).isNotEmpty();
        assertThat(error.getValidationErrors().get(0).getMessage()).contains("Window must be in format");
    }

    @Test
    void testValidRequestSucceeds() throws Exception {
        System.out.println("=== Testing Valid Request Succeeds ===");
        
        RateLimitRequest request = createRequest("test-key", "token_bucket", 10L, "60s", 1);
        
        mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        
        System.out.println("✅ Valid request processed successfully");
    }

    @Test
    void testErrorResponseFormat() throws Exception {
        System.out.println("=== Testing Error Response Format ===");
        
        RateLimitRequest request = createRequest("", "token_bucket", 10L, "60s", 1);
        
        MvcResult result = mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = objectMapper.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
        
        // Verify standard error response format
        assertThat(error.getError()).isNotNull();
        assertThat(error.getMessage()).isNotNull();
        assertThat(error.getTimestamp()).isNotNull();
        assertThat(error.getPath()).isNotNull();
        
        System.out.printf("Standard error format: error=%s, message=%s, timestamp=%s, path=%s\n",
            error.getError(), error.getMessage(), error.getTimestamp(), error.getPath());
        
        System.out.println("✅ Error response format is standardized");
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