package com.ratelimiter.service;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;

/**
 * Core service interface for rate limiting operations.
 * 
 * This service coordinates between the REST layer and the underlying
 * rate limiting strategies. It handles algorithm selection, configuration
 * validation, and delegates to appropriate strategy implementations.
 */
public interface RateLimiterService {
    
    /**
     * Check if a request should be allowed based on the configured rate limits.
     * 
     * @param request the rate limit request containing key, algorithm, and limits
     * @return rate limit decision with remaining quota and timing information
     * @throws IllegalArgumentException if the request contains invalid parameters
     */
    RateLimitResponse checkRateLimit(RateLimitRequest request);
    
    /**
     * Validate that the given request parameters are valid for rate limiting.
     * 
     * @param request the request to validate
     * @throws IllegalArgumentException if the request is invalid
     */
    void validateRequest(RateLimitRequest request);
}