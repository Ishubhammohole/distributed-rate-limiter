package com.ratelimiter.controller;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.service.RateLimiterService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for rate limiting operations.
 * Provides the main API endpoint for rate limit checks.
 */
@RestController
@RequestMapping("/api/v1/ratelimit")
public class RateLimitController {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitController.class);
    
    private final RateLimiterService rateLimiterService;
    
    public RateLimitController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * Check rate limit for a given key and configuration.
     * 
     * **IMPORTANT: Rate limiting strategies may use MOCK behavior during development**
     * **Real rate limiting algorithms will be implemented in Phase 2.x**
     * 
     * @param request the rate limit request containing key, algorithm, and limits
     * @return rate limit decision with remaining quota and timing information
     */
    @PostMapping("/check")
    public ResponseEntity<RateLimitResponse> checkRateLimit(@Valid @RequestBody RateLimitRequest request) {
        logger.debug("Rate limit check request: {}", request);

        RateLimitResponse response = rateLimiterService.checkRateLimit(request);
        
        logger.debug("Rate limit check response: {}", response);
        return ResponseEntity.ok(response);
    }
}