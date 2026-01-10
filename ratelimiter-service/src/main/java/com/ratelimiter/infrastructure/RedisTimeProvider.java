package com.ratelimiter.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Provides authoritative time using Redis TIME command.
 * 
 * This ensures all rate limiting decisions use the same time source,
 * which is critical for distributed rate limiting consistency.
 * Falls back to system time if Redis is unavailable.
 */
@Component
public class RedisTimeProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisTimeProvider.class);
    
    private final LuaScriptExecutor scriptExecutor;
    
    // Lua script to get current time in milliseconds
    private static final String TIME_SCRIPT = """
        local time = redis.call('TIME')
        return time[1] * 1000 + math.floor(time[2] / 1000)
        """;
    
    public RedisTimeProvider(LuaScriptExecutor scriptExecutor) {
        this.scriptExecutor = scriptExecutor;
    }
    
    /**
     * Get current time from Redis TIME command.
     * 
     * @return current time as Instant
     */
    public Instant getCurrentTime() {
        return getCurrentTime(true);
    }
    
    /**
     * Get current time from Redis with explicit fallback behavior.
     * 
     * @param allowFallback whether to fall back to system time if Redis fails
     * @return current time as Instant
     * @throws RuntimeException if Redis fails and fallback is not allowed
     */
    public Instant getCurrentTime(boolean allowFallback) {
        try {
            Long timestampMillis = scriptExecutor.executeLong(TIME_SCRIPT, java.util.List.of());
            if (timestampMillis != null) {
                return Instant.ofEpochMilli(timestampMillis);
            }
        } catch (Exception e) {
            if (allowFallback) {
                logger.warn("Failed to get time from Redis, falling back to system time", e);
                return Instant.now();
            } else {
                throw new RuntimeException("Failed to get authoritative time from Redis", e);
            }
        }
        
        if (allowFallback) {
            logger.warn("Redis TIME command returned null, falling back to system time");
            return Instant.now();
        } else {
            throw new RuntimeException("Redis TIME command returned invalid response");
        }
    }
    
    /**
     * Get current Unix timestamp in seconds from Redis.
     * 
     * @return current Unix timestamp in seconds
     */
    public long getCurrentTimestamp() {
        return getCurrentTime().getEpochSecond();
    }
    
    /**
     * Get current Unix timestamp in milliseconds from Redis.
     * 
     * @return current Unix timestamp in milliseconds
     */
    public long getCurrentTimestampMillis() {
        Instant now = getCurrentTime();
        return now.toEpochMilli();
    }
}