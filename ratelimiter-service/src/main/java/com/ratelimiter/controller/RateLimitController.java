package com.ratelimiter.controller;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * REST controller for rate limiting operations.
 * Provides the main API endpoint for rate limit checks.
 */
@RestController
@RequestMapping("/api/v1/ratelimit")
public class RateLimitController {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitController.class);

    /**
     * Check rate limit for a given key and configuration.
     * 
     * **IMPORTANT: This is currently using MOCK behavior for Phase 1.1**
     * **Real rate limiting logic will be implemented in Phase 1.2/2.x**
     * 
     * @param request the rate limit request containing key, algorithm, and limits
     * @return rate limit decision with remaining quota and timing information
     */
    @PostMapping("/check")
    public ResponseEntity<RateLimitResponse> checkRateLimit(@Valid @RequestBody RateLimitRequest request) {
        logger.debug("Rate limit check request: {}", request);

        // TODO: Replace with actual rate limiting logic in Phase 1.2
        // Currently using mock responses to validate API contract
        RateLimitResponse response = createMockResponse(request);
        
        logger.debug("Rate limit check response: {}", response);
        return ResponseEntity.ok(response);
    }

    /**
     * Create a mock response for testing the API contract.
     * 
     * **MOCK BEHAVIOR - NOT REAL RATE LIMITING**
     * This will be replaced with actual Redis-based rate limiting logic.
     */
    private RateLimitResponse createMockResponse(RateLimitRequest request) {
        // Mock logic: allow first 5 requests, then deny
        long remaining = Math.max(0, request.getLimit() - 1);
        boolean allowed = remaining > 0;
        
        Instant resetTime = Instant.now().plusSeconds(parseWindowToSeconds(request.getWindow()));
        
        if (allowed) {
            return RateLimitResponse.allowed(remaining, resetTime);
        } else {
            return RateLimitResponse.denied(0, resetTime);
        }
    }

    /**
     * Parse window string to seconds.
     * Supports formats like "60s", "5m", "1h", "1d".
     */
    private long parseWindowToSeconds(String window) {
        if (window == null || window.isEmpty()) {
            return 60; // Default to 60 seconds
        }

        String unit = window.substring(window.length() - 1);
        long value = Long.parseLong(window.substring(0, window.length() - 1));

        return switch (unit) {
            case "s" -> value;
            case "m" -> value * 60;
            case "h" -> value * 3600;
            case "d" -> value * 86400;
            default -> 60; // Default to 60 seconds
        };
    }
}