package com.ratelimiter.strategy;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;

/**
 * Strategy interface for rate limiting algorithm implementations.
 * 
 * Each strategy encapsulates the logic for a specific rate limiting algorithm,
 * including the Redis operations and Lua script execution required to
 * atomically check and update rate limit state.
 */
public interface RateLimiterStrategy {
    
    /**
     * Execute the rate limiting check for this strategy.
     * 
     * @param request the rate limit request
     * @return the rate limit decision
     */
    RateLimitResponse execute(RateLimitRequest request);
    
    /**
     * Get the algorithm this strategy implements.
     * 
     * @return the rate limiting algorithm
     */
    RateLimitAlgorithm getAlgorithm();
    
    /**
     * Validate that the request parameters are valid for this strategy.
     * 
     * @param request the request to validate
     * @throws IllegalArgumentException if parameters are invalid for this strategy
     */
    void validateRequest(RateLimitRequest request);
}