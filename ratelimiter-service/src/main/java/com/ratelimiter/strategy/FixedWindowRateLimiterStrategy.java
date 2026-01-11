package com.ratelimiter.strategy;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.infrastructure.LuaScriptExecutor;
import com.ratelimiter.infrastructure.RedisTimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Fixed Window rate limiting strategy implementation.
 * 
 * Uses Redis with Lua scripts for atomic operations and Redis TIME for consistent timing.
 * Implements a fixed window counter algorithm that divides time into fixed windows
 * and counts requests within each window. Simple and memory-efficient but allows
 * bursts at window boundaries.
 */
@Component
public class FixedWindowRateLimiterStrategy implements RateLimiterStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(FixedWindowRateLimiterStrategy.class);
    
    private final LuaScriptExecutor scriptExecutor;
    private final RedisTimeProvider timeProvider;
    
    /**
     * Lua script for atomic fixed window operations.
     * 
     * Redis data model:
     * - Key: rl:{key}:fw:{window_start_ms}
     * - Value: current request count in this window
     * 
     * Script inputs:
     * - KEYS[1]: Redis key for this rate limit window
     * - ARGV[1]: current time in milliseconds
     * - ARGV[2]: window size in milliseconds
     * - ARGV[3]: limit (max requests in window)
     * - ARGV[4]: cost (number of requests this call represents)
     * 
     * Returns: {allowed (1/0), remaining_requests, reset_time_ms}
     */
    private static final String FIXED_WINDOW_SCRIPT = """
        local key = KEYS[1]
        local now_ms = tonumber(ARGV[1])
        local window_ms = tonumber(ARGV[2])
        local limit = tonumber(ARGV[3])
        local cost = tonumber(ARGV[4])
        
        -- Calculate window start time (align to window boundaries)
        local window_start_ms = math.floor(now_ms / window_ms) * window_ms
        local window_key = key .. ':' .. window_start_ms
        
        -- Get current count for this window
        local current_count = tonumber(redis.call('GET', window_key)) or 0
        
        -- Check if request can be allowed
        local allowed = 0
        local remaining_requests = math.max(0, limit - current_count)
        
        if current_count + cost <= limit then
            allowed = 1
            
            -- Increment counter
            redis.call('INCRBY', window_key, cost)
            
            -- Update remaining count after adding
            remaining_requests = math.max(0, limit - current_count - cost)
        end
        
        -- Calculate reset time (start of next window)
        local reset_time_ms = window_start_ms + window_ms
        
        -- Set expiration (window size + buffer for cleanup)
        local expire_seconds = math.max(60, math.ceil(window_ms / 1000) + 60)
        redis.call('EXPIRE', window_key, expire_seconds)
        
        -- Return: allowed, remaining_requests, reset_time_ms
        return {allowed, remaining_requests, reset_time_ms}
        """;
    
    public FixedWindowRateLimiterStrategy(LuaScriptExecutor scriptExecutor, RedisTimeProvider timeProvider) {
        this.scriptExecutor = scriptExecutor;
        this.timeProvider = timeProvider;
    }
    
    @Override
    public RateLimitResponse execute(RateLimitRequest request) {
        validateRequest(request);
        
        try {
            // Parse window to milliseconds
            long windowMs = parseWindowToMilliseconds(request.getWindow());
            
            // Get current time from Redis
            long nowMs = timeProvider.getCurrentTimestampMillis();
            
            // Build Redis key
            String redisKey = "rl:" + request.getKey() + ":fw";
            
            // Execute Lua script
            List<Object> result = scriptExecutor.executeList(
                FIXED_WINDOW_SCRIPT,
                List.of(redisKey),
                nowMs,
                windowMs,
                request.getLimit(),
                request.getCost()
            );
            
            // Parse results
            long allowed = ((Number) result.get(0)).longValue();
            long remainingRequests = ((Number) result.get(1)).longValue();
            long resetTimeMs = ((Number) result.get(2)).longValue();
            
            Instant resetTime = Instant.ofEpochMilli(resetTimeMs);
            
            if (allowed == 1) {
                logger.debug("Fixed window allowed request for key={}, remaining={}", 
                    request.getKey(), remainingRequests);
                return RateLimitResponse.allowed(remainingRequests, resetTime);
            } else {
                logger.debug("Fixed window denied request for key={}, remaining={}", 
                    request.getKey(), remainingRequests);
                return RateLimitResponse.denied(remainingRequests, resetTime);
            }
            
        } catch (Exception e) {
            logger.error("Fixed window execution failed for key={}", request.getKey(), e);
            throw new RuntimeException("Fixed window rate limiting failed", e);
        }
    }
    
    @Override
    public RateLimitAlgorithm getAlgorithm() {
        return RateLimitAlgorithm.FIXED_WINDOW;
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
        
        if (request.getWindow() == null || request.getWindow().trim().isEmpty()) {
            throw new IllegalArgumentException("Window cannot be null or empty");
        }
        
        // Validate window format
        try {
            parseWindowToMilliseconds(request.getWindow());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid window format: " + request.getWindow(), e);
        }
    }
    
    /**
     * Parse window string to milliseconds.
     * Supports: 1s, 30s, 1m, 5m, 1h, etc.
     */
    private long parseWindowToMilliseconds(String window) {
        if (window == null || window.isEmpty()) {
            throw new IllegalArgumentException("Window cannot be null or empty");
        }
        
        window = window.trim().toLowerCase();
        
        if (window.length() < 2) {
            throw new IllegalArgumentException("Window must have value and unit (e.g., '60s')");
        }
        
        String unit = window.substring(window.length() - 1);
        String valueStr = window.substring(0, window.length() - 1);
        
        long value;
        try {
            value = Long.parseLong(valueStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid window value: " + valueStr, e);
        }
        
        if (value <= 0) {
            throw new IllegalArgumentException("Window value must be positive: " + value);
        }
        
        return switch (unit) {
            case "s" -> value * 1000L;
            case "m" -> value * 60L * 1000L;
            case "h" -> value * 60L * 60L * 1000L;
            case "d" -> value * 24L * 60L * 60L * 1000L;
            default -> throw new IllegalArgumentException("Unsupported window unit: " + unit + ". Supported: s, m, h, d");
        };
    }
}