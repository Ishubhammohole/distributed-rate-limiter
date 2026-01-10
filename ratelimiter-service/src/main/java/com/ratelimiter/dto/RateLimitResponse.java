package com.ratelimiter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Response DTO for rate limit checks.
 * Contains the decision and metadata about the rate limit state.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RateLimitResponse {

    private boolean allowed;
    private long remaining;
    private Instant resetTime;
    private String retryAfter;

    // Default constructor
    public RateLimitResponse() {}

    // Constructor for allowed responses
    public RateLimitResponse(boolean allowed, long remaining, Instant resetTime) {
        this.allowed = allowed;
        this.remaining = remaining;
        this.resetTime = resetTime;
        this.retryAfter = allowed ? null : calculateRetryAfter(resetTime);
    }

    // Constructor with explicit retry after
    public RateLimitResponse(boolean allowed, long remaining, Instant resetTime, String retryAfter) {
        this.allowed = allowed;
        this.remaining = remaining;
        this.resetTime = resetTime;
        this.retryAfter = retryAfter;
    }

    /**
     * Calculate retry-after duration based on reset time.
     */
    private String calculateRetryAfter(Instant resetTime) {
        if (resetTime == null) {
            return null;
        }
        
        long secondsUntilReset = resetTime.getEpochSecond() - Instant.now().getEpochSecond();
        if (secondsUntilReset <= 0) {
            return "0s";
        }
        
        return secondsUntilReset + "s";
    }

    // Static factory methods for common responses
    public static RateLimitResponse allowed(long remaining, Instant resetTime) {
        return new RateLimitResponse(true, remaining, resetTime);
    }

    public static RateLimitResponse denied(long remaining, Instant resetTime) {
        return new RateLimitResponse(false, remaining, resetTime);
    }

    public static RateLimitResponse denied(long remaining, Instant resetTime, String retryAfter) {
        return new RateLimitResponse(false, remaining, resetTime, retryAfter);
    }

    // Getters and setters
    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public long getRemaining() {
        return remaining;
    }

    public void setRemaining(long remaining) {
        this.remaining = remaining;
    }

    public Instant getResetTime() {
        return resetTime;
    }

    public void setResetTime(Instant resetTime) {
        this.resetTime = resetTime;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    public void setRetryAfter(String retryAfter) {
        this.retryAfter = retryAfter;
    }

    @Override
    public String toString() {
        return "RateLimitResponse{" +
                "allowed=" + allowed +
                ", remaining=" + remaining +
                ", resetTime=" + resetTime +
                ", retryAfter='" + retryAfter + '\'' +
                '}';
    }
}