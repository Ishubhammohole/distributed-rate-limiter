package com.ratelimiter.strategy;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.infrastructure.LuaScriptExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Token Bucket rate limiting strategy implementation.
 * 
 * Uses Redis with Lua scripts for atomic operations and Redis TIME for consistent timing.
 * Implements a token bucket algorithm where tokens are refilled at a constant rate
 * and requests consume tokens from the bucket.
 */
@Component
public class TokenBucketRateLimiterStrategy implements RateLimiterStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(TokenBucketRateLimiterStrategy.class);
    
    private final LuaScriptExecutor scriptExecutor;
    private final com.ratelimiter.infrastructure.RedisTimeProvider timeProvider;
    
    /**
     * Lua script for atomic token bucket operations.
     * 
     * Redis data model:
     * - Key: rl:{key}:tb
     * - Hash fields:
     *   - tokens: current available tokens (stored as integer microtokens to avoid float precision issues)
     *   - last_refill_ms: last refill timestamp in milliseconds
     * 
     * Script inputs:
     * - KEYS[1]: Redis key for this rate limit bucket
     * - ARGV[1]: bucket capacity (max tokens)
     * - ARGV[2]: refill rate (tokens per millisecond, as microtokens)
     * - ARGV[3]: cost (tokens to consume)
     * 
     * Returns: {allowed (1/0), remaining_tokens, reset_time_ms}
     */
    private static final String TOKEN_BUCKET_SCRIPT = """
        local key = KEYS[1]
        local time = redis.call('TIME')
        local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
        local capacity = tonumber(ARGV[2])
        local refill_rate_micro = tonumber(ARGV[3])  -- microtokens per ms
        local cost = tonumber(ARGV[4])
        
        -- Get current state (use HMGET for atomic read)
        local state = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
        local current_tokens_micro = tonumber(state[1]) or (capacity * 1000000)  -- Initialize to full capacity
        local last_refill_ms = tonumber(state[2]) or now_ms
        
        -- Calculate refill amount (handle time going backwards gracefully)
        local time_delta_ms = math.max(0, now_ms - last_refill_ms)
        local refill_micro = time_delta_ms * refill_rate_micro
        
        -- Update tokens (clamp to capacity)
        local new_tokens_micro = math.min(capacity * 1000000, current_tokens_micro + refill_micro)
        
        -- Check if request can be allowed
        local cost_micro = cost * 1000000
        local allowed = 0
        local remaining_tokens_micro = new_tokens_micro
        
        if new_tokens_micro >= cost_micro then
            allowed = 1
            remaining_tokens_micro = new_tokens_micro - cost_micro
        end
        
        -- Calculate reset time (when enough tokens will be available for next request)
        local reset_time_ms = now_ms
        if remaining_tokens_micro < 1000000 then  -- Less than 1 token remaining
            local tokens_needed_micro = 1000000 - remaining_tokens_micro
            local time_to_refill_ms = math.ceil(tokens_needed_micro / refill_rate_micro)
            reset_time_ms = now_ms + time_to_refill_ms
        end
        
        -- Persist updated state
        redis.call('HMSET', key, 'tokens', remaining_tokens_micro, 'last_refill_ms', now_ms)
        
        -- Set expiration (2x the time to fully refill bucket)
        local full_refill_time_ms = math.ceil((capacity * 1000000) / refill_rate_micro)
        local expire_seconds = math.max(60, math.ceil((full_refill_time_ms * 2) / 1000))
        redis.call('EXPIRE', key, expire_seconds)
        
        -- Return: allowed, remaining_tokens (as regular tokens), reset_time_ms
        return {allowed, math.floor(remaining_tokens_micro / 1000000), reset_time_ms}
        """;
    
    @Autowired
    public TokenBucketRateLimiterStrategy(LuaScriptExecutor scriptExecutor) {
        this.scriptExecutor = scriptExecutor;
        this.timeProvider = null;
    }

    TokenBucketRateLimiterStrategy(LuaScriptExecutor scriptExecutor, com.ratelimiter.infrastructure.RedisTimeProvider timeProvider) {
        this.scriptExecutor = scriptExecutor;
        this.timeProvider = timeProvider;
    }
    
    @Override
    public RateLimitResponse execute(RateLimitRequest request) {
        validateRequest(request);
        
        try {
            // Parse window to get refill rate
            long windowMs = parseWindowToMilliseconds(request.getWindow());
            double refillRatePerMs = (double) request.getLimit() / windowMs;
            long refillRateMicroPerMs = Math.round(refillRatePerMs * 1_000_000); // Convert to microtokens per ms
            
            // Build Redis key
            String redisKey = "rl:" + request.getKey() + ":tb";
            
            // Execute Lua script
            List<Object> result = scriptExecutor.executeList(
                TOKEN_BUCKET_SCRIPT,
                List.of(redisKey),
                currentTimeHintMillis(),
                request.getLimit(),
                refillRateMicroPerMs,
                request.getCost()
            );
            
            // Parse results
            long allowed = ((Number) result.get(0)).longValue();
            long remainingTokens = ((Number) result.get(1)).longValue();
            long resetTimeMs = ((Number) result.get(2)).longValue();
            
            Instant resetTime = Instant.ofEpochMilli(resetTimeMs);
            
            if (allowed == 1) {
                logger.debug("Token bucket allowed request for key={}, remaining={}", request.getKey(), remainingTokens);
                return RateLimitResponse.allowed(remainingTokens, resetTime);
            } else {
                logger.debug("Token bucket denied request for key={}, remaining={}", request.getKey(), remainingTokens);
                return RateLimitResponse.denied(remainingTokens, resetTime);
            }
            
        } catch (Exception e) {
            logger.error("Token bucket execution failed for key={}", request.getKey(), e);
            throw new RuntimeException("Token bucket rate limiting failed", e);
        }
    }
    
    @Override
    public RateLimitAlgorithm getAlgorithm() {
        return RateLimitAlgorithm.TOKEN_BUCKET;
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

    private long currentTimeHintMillis() {
        if (timeProvider == null) {
            return 0L;
        }
        return timeProvider.getCurrentTimestampMillis();
    }
}
