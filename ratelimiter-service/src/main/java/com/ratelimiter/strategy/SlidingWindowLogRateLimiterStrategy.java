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
 * Sliding Window Log rate limiting strategy implementation.
 * 
 * Uses Redis with Lua scripts for atomic operations and Redis TIME for consistent timing.
 * Implements a sliding window log algorithm that tracks individual request timestamps
 * in a sorted set, providing perfect accuracy at the cost of memory proportional to
 * the number of requests in the window.
 */
@Component
public class SlidingWindowLogRateLimiterStrategy implements RateLimiterStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(SlidingWindowLogRateLimiterStrategy.class);
    
    private final LuaScriptExecutor scriptExecutor;
    private final RedisTimeProvider timeProvider;
    
    /**
     * Lua script for atomic sliding window log operations.
     * 
     * Redis data model:
     * - Key: rl:{key}:swl
     * - Data structure: Sorted Set (ZSET)
     * - Members: request_id (unique per request)
     * - Scores: timestamp in milliseconds
     * 
     * Script inputs:
     * - KEYS[1]: Redis key for this rate limit window
     * - ARGV[1]: current time in milliseconds
     * - ARGV[2]: window size in milliseconds
     * - ARGV[3]: limit (max requests in window)
     * - ARGV[4]: cost (number of requests this call represents)
     * - ARGV[5]: unique request identifier
     * 
     * Returns: {allowed (1/0), remaining_requests, reset_time_ms}
     */
    private static final String SLIDING_WINDOW_LOG_SCRIPT = """
        local key = KEYS[1]
        local now_ms = tonumber(ARGV[1])
        local window_ms = tonumber(ARGV[2])
        local limit = tonumber(ARGV[3])
        local cost = tonumber(ARGV[4])
        local request_id = ARGV[5]
        
        -- Calculate window start time
        local window_start_ms = now_ms - window_ms
        
        -- Remove expired entries (older than window start)
        redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start_ms)
        
        -- Count current requests in window
        local current_count = redis.call('ZCARD', key)
        
        -- Check if request can be allowed
        local allowed = 0
        local remaining_requests = math.max(0, limit - current_count)
        
        if current_count + cost <= limit then
            allowed = 1
            
            -- Add new request entries to the log
            for i = 1, cost do
                local entry_id = request_id .. ':' .. i
                redis.call('ZADD', key, now_ms, entry_id)
            end
            
            -- Update remaining count after adding
            remaining_requests = math.max(0, limit - current_count - cost)
        end
        
        -- Calculate reset time (when the oldest request in window will expire)
        local reset_time_ms = now_ms
        if current_count > 0 then
            -- Get the oldest entry in the window
            local oldest_entries = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            if #oldest_entries >= 2 then
                local oldest_timestamp = tonumber(oldest_entries[2])
                reset_time_ms = oldest_timestamp + window_ms
            end
        end
        
        -- Set expiration (window size + buffer for cleanup)
        local expire_seconds = math.max(60, math.ceil(window_ms / 1000) + 60)
        redis.call('EXPIRE', key, expire_seconds)
        
        -- Return: allowed, remaining_requests, reset_time_ms
        return {allowed, remaining_requests, reset_time_ms}
        """;
    
    public SlidingWindowLogRateLimiterStrategy(LuaScriptExecutor scriptExecutor, RedisTimeProvider timeProvider) {
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
            String redisKey = "rl:" + request.getKey() + ":swl";
            
            // Generate unique request identifier
            String requestId = generateRequestId(request.getKey(), nowMs);
            
            // Execute Lua script
            List<Object> result = scriptExecutor.executeList(
                SLIDING_WINDOW_LOG_SCRIPT,
                List.of(redisKey),
                nowMs,
                windowMs,
                request.getLimit(),
                request.getCost(),
                requestId
            );
            
            // Parse results
            long allowed = ((Number) result.get(0)).longValue();
            long remainingRequests = ((Number) result.get(1)).longValue();
            long resetTimeMs = ((Number) result.get(2)).longValue();
            
            Instant resetTime = Instant.ofEpochMilli(resetTimeMs);
            
            if (allowed == 1) {
                logger.debug("Sliding window log allowed request for key={}, remaining={}", 
                    request.getKey(), remainingRequests);
                return RateLimitResponse.allowed(remainingRequests, resetTime);
            } else {
                logger.debug("Sliding window log denied request for key={}, remaining={}", 
                    request.getKey(), remainingRequests);
                return RateLimitResponse.denied(remainingRequests, resetTime);
            }
            
        } catch (Exception e) {
            logger.error("Sliding window log execution failed for key={}", request.getKey(), e);
            throw new RuntimeException("Sliding window log rate limiting failed", e);
        }
    }
    
    @Override
    public RateLimitAlgorithm getAlgorithm() {
        return RateLimitAlgorithm.SLIDING_WINDOW_LOG;
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
    
    /**
     * Generate a unique request identifier for this request.
     * Uses key and timestamp to ensure uniqueness across concurrent requests.
     */
    private String generateRequestId(String key, long timestampMs) {
        // Use a combination of key hash and timestamp to ensure uniqueness
        // Add thread ID to handle high concurrency on same key
        return String.format("%d_%d_%d", 
            key.hashCode(), 
            timestampMs, 
            Thread.currentThread().getId());
    }
}