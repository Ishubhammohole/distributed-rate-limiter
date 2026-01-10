package com.ratelimiter.service;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.strategy.RateLimiterStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Default implementation of RateLimiterService.
 * 
 * Coordinates between the REST layer and rate limiting strategies.
 * Handles algorithm selection and delegates to appropriate strategy implementations.
 */
@Service
public class RateLimiterServiceImpl implements RateLimiterService {
    
    private static final Logger logger = LoggerFactory.getLogger(RateLimiterServiceImpl.class);
    
    private final Map<RateLimitAlgorithm, RateLimiterStrategy> strategies;
    
    public RateLimiterServiceImpl(Map<RateLimitAlgorithm, RateLimiterStrategy> strategies) {
        this.strategies = strategies;
        logger.info("Initialized RateLimiterService with {} strategies", strategies.size());
    }
    
    @Override
    public RateLimitResponse checkRateLimit(RateLimitRequest request) {
        logger.debug("Processing rate limit request: {}", request);
        
        validateRequest(request);
        
        RateLimitAlgorithm algorithm = RateLimitAlgorithm.fromValue(request.getAlgorithm());
        RateLimiterStrategy strategy = strategies.get(algorithm);
        
        if (strategy == null) {
            // TODO: Remove this fallback when all strategies are implemented in Phase 2
            logger.warn("No strategy found for algorithm {}, using mock response", algorithm);
            return createMockResponse(request);
        }
        
        RateLimitResponse response = strategy.execute(request);
        logger.debug("Rate limit response: {}", response);
        
        return response;
    }
    
    @Override
    public void validateRequest(RateLimitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        
        if (request.getKey() == null || request.getKey().trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
        
        if (request.getLimit() == null || request.getLimit() <= 0) {
            throw new IllegalArgumentException("Limit must be positive");
        }
        
        if (request.getCost() <= 0) {
            throw new IllegalArgumentException("Cost must be positive");
        }
        
        // Validate algorithm exists
        try {
            RateLimitAlgorithm.fromValue(request.getAlgorithm());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid algorithm: " + request.getAlgorithm(), e);
        }
    }
    
    /**
     * Create a mock response for testing the service layer.
     * TODO: Remove this method when all strategies are implemented in Phase 2.
     */
    private RateLimitResponse createMockResponse(RateLimitRequest request) {
        // Mock logic: allow first few requests, then deny
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
     * TODO: Move to a utility class when window parsing becomes more sophisticated.
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