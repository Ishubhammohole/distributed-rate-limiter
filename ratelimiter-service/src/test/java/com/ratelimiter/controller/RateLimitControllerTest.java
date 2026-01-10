package com.ratelimiter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.dto.RateLimitRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for RateLimitController.
 * Tests the API contract without Redis dependencies.
 */
@WebMvcTest(RateLimitController.class)
class RateLimitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void checkRateLimit_ValidRequest_ReturnsOk() throws Exception {
        RateLimitRequest request = new RateLimitRequest(
            "user:123", 
            "token_bucket", 
            100L, 
            "60s", 
            1
        );

        mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").exists())
                .andExpect(jsonPath("$.remaining").exists())
                .andExpect(jsonPath("$.resetTime").exists());
    }

    @Test
    void checkRateLimit_InvalidKey_ReturnsBadRequest() throws Exception {
        RateLimitRequest request = new RateLimitRequest(
            "", // Invalid empty key
            "token_bucket", 
            100L, 
            "60s", 
            1
        );

        mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.validationErrors").exists());
    }

    @Test
    void checkRateLimit_InvalidAlgorithm_ReturnsBadRequest() throws Exception {
        RateLimitRequest request = new RateLimitRequest(
            "user:123", 
            "invalid_algorithm", 
            100L, 
            "60s", 
            1
        );

        mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.validationErrors").exists());
    }

    @Test
    void checkRateLimit_InvalidWindow_ReturnsBadRequest() throws Exception {
        RateLimitRequest request = new RateLimitRequest(
            "user:123", 
            "token_bucket", 
            100L, 
            "invalid", // Invalid window format
            1
        );

        mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.validationErrors").exists());
    }

    @Test
    void checkRateLimit_NegativeLimit_ReturnsBadRequest() throws Exception {
        RateLimitRequest request = new RateLimitRequest(
            "user:123", 
            "token_bucket", 
            -1L, // Invalid negative limit
            "60s", 
            1
        );

        mockMvc.perform(post("/api/v1/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.validationErrors").exists());
    }
}