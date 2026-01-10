package com.ratelimiter.domain;

/**
 * Enumeration of supported rate limiting algorithms.
 * Each algorithm has different characteristics for accuracy, memory usage, and burst handling.
 */
public enum RateLimitAlgorithm {
    
    /**
     * Token bucket algorithm - allows bursts while maintaining average rate.
     * Good for: API rate limiting with burst tolerance
     * Memory: O(1) per key
     * Accuracy: High
     */
    TOKEN_BUCKET("token_bucket"),
    
    /**
     * Sliding window log algorithm - precise tracking of all requests.
     * Good for: When perfect accuracy is required
     * Memory: O(n) where n is requests in window
     * Accuracy: Perfect
     */
    SLIDING_WINDOW_LOG("sliding_window_log"),
    
    /**
     * Fixed window counter algorithm - simple quotas with window resets.
     * Good for: Simple rate limiting with acceptable edge-case bursts
     * Memory: O(1) per key
     * Accuracy: Good (edge-case bursts at boundaries)
     */
    FIXED_WINDOW("fixed_window"),
    
    /**
     * Sliding window counter algorithm - approximates sliding window with constant memory.
     * Good for: Balance of accuracy and memory efficiency
     * Memory: O(1) per key
     * Accuracy: High approximation
     */
    SLIDING_WINDOW_COUNTER("sliding_window_counter");
    
    private final String value;
    
    RateLimitAlgorithm(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    /**
     * Parse algorithm from string value.
     */
    public static RateLimitAlgorithm fromValue(String value) {
        for (RateLimitAlgorithm algorithm : values()) {
            if (algorithm.value.equals(value)) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("Unknown rate limit algorithm: " + value);
    }
}