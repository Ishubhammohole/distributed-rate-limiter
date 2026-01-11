package com.ratelimiter.strategy;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.infrastructure.LuaScriptExecutor;
import com.ratelimiter.infrastructure.RedisTimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Sliding Window Counter rate limiting strategy implementation.
 * 
 * Provides memory-efficient approximate rate limiting by maintaining only two counters
 * (current and previous window) and weighting the previous window's contribution based
 * on time overlap. This achieves O(1) memory usage per key while providing good accuracy
 * compared to the exact sliding window log approach.
 * 
 * Algorithm characteristics:
 * - Memory: O(1) per key (2 Redis keys maximum)
 * - Accuracy: 90-95% typical, ~50% worst case during window transitions
 * - Performance: <1ms Redis operations, supports 10,000+ RPS
 * - Use case: Balance of accuracy and memory efficiency
 */
@Component
public class SlidingWindowCounterRateLimiterStrategy implements RateLimiterStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(SlidingWindowCounterRateLimiterStrategy.class);
    
    private final LuaScriptExecutor scriptExecutor;
    private final RedisTimeProvider timeProvider;
    private final Clock clock;
    private final String luaScript;
    
    /**
     * Redis key pattern for sliding window counter.
     * Pattern: rate_limit:sliding_window_counter:{userKey}:{window_type}
     */
    private static final String KEY_PREFIX = "rate_limit:sliding_window_counter:";
    private static final String CURRENT_SUFFIX = ":current";
    private static final String PREVIOUS_SUFFIX = ":previous";
    
    public SlidingWindowCounterRateLimiterStrategy(
            LuaScriptExecutor scriptExecutor, 
            RedisTimeProvider timeProvider,
            Clock clock) {
        this.scriptExecutor = scriptExecutor;
        this.timeProvider = timeProvider;
        this.clock = clock;
        this.luaScript = loadLuaScript();
    }
    
    @Override
    public RateLimitResponse execute(RateLimitRequest request) {
        validateRequest(request);
        
        try {
            long windowSizeMillis = parseWindowToMilliseconds(request.getWindow());
            long currentTimeMillis = timeProvider.getCurrentTimestampMillis();
            
            // Calculate window boundaries
            long currentWindow = calculateCurrentWindow(currentTimeMillis, windowSizeMillis);
            long previousWindow = currentWindow - 1;
            
            // Calculate previous window weight based on time overlap
            double previousWindowWeight = calculatePreviousWindowWeight(currentTimeMillis, windowSizeMillis);
            
            // Build Redis keys
            String currentWindowKey = buildCurrentWindowKey(request.getKey());
            String previousWindowKey = buildPreviousWindowKey(request.getKey());
            
            // Calculate TTL (2x window size to ensure previous window availability)
            long ttlSeconds = Math.max(60L, (windowSizeMillis * 2) / 1000L);
            
            // Execute Lua script atomically with window-aware parameters
            List<Object> result = scriptExecutor.executeList(
                luaScript,
                List.of(currentWindowKey, previousWindowKey),
                request.getLimit(),
                request.getCost(),
                currentTimeMillis,
                windowSizeMillis,
                currentWindow * windowSizeMillis, // currentWindowStart
                previousWindow * windowSizeMillis, // previousWindowStart
                ttlSeconds
            );
            
            // Parse script results: [allowed, remaining, currentCount, previousCount, weight, retryAfterSeconds?]
            if (result.isEmpty()) {
                throw new RuntimeException("Lua script returned empty result");
            }
            
            long allowed = ((Number) result.get(0)).longValue();
            long remaining = ((Number) result.get(1)).longValue();
            long currentCount = ((Number) result.get(2)).longValue();
            long previousCount = ((Number) result.get(3)).longValue();
            double actualWeight = result.size() > 4 ? ((Number) result.get(4)).doubleValue() : 0.0;
            
            // Calculate reset time (next window boundary)
            Instant resetTime = calculateResetTime(currentWindow, windowSizeMillis);
            
            if (allowed == 1) {
                logger.debug("Sliding window counter allowed request for key={}, remaining={}, current={}, previous={}, weight={}", 
                    request.getKey(), remaining, currentCount, previousCount, actualWeight);
                return RateLimitResponse.allowed(remaining, resetTime);
            } else {
                // Get retryAfter from Lua script if available, otherwise calculate
                long retryAfterSeconds = result.size() > 5 ? ((Number) result.get(5)).longValue() : 
                    (resetTime.toEpochMilli() - currentTimeMillis) / 1000L;
                
                logger.debug("Sliding window counter denied request for key={}, remaining={}, current={}, previous={}, weight={}, retryAfter={}s", 
                    request.getKey(), remaining, currentCount, previousCount, actualWeight, retryAfterSeconds);
                return RateLimitResponse.denied(remaining, resetTime);
            }
            
        } catch (Exception e) {
            logger.error("Sliding window counter execution failed for key={}, failing open", request.getKey(), e);
            
            // Fail-open behavior: allow request with conservative remaining estimate
            Instant resetTime = Instant.now(clock).plusMillis(parseWindowToMilliseconds(request.getWindow()));
            return RateLimitResponse.allowed(request.getLimit() - 1, resetTime);
        }
    }
    
    @Override
    public RateLimitAlgorithm getAlgorithm() {
        return RateLimitAlgorithm.SLIDING_WINDOW_COUNTER;
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
     * Calculate current window identifier based on timestamp and window size.
     * Windows are aligned to epoch boundaries for consistency across instances.
     * 
     * @param currentTimeMillis current timestamp in milliseconds
     * @param windowSizeMillis window size in milliseconds
     * @return current window identifier
     */
    private long calculateCurrentWindow(long currentTimeMillis, long windowSizeMillis) {
        return currentTimeMillis / windowSizeMillis;
    }
    
    /**
     * Calculate the weight factor for the previous window based on time overlap.
     * The weight represents how much of the previous window should contribute
     * to the current rate calculation.
     * 
     * Formula: (windowSize - timeIntoCurrentWindow) / windowSize
     * 
     * @param currentTimeMillis current timestamp in milliseconds
     * @param windowSizeMillis window size in milliseconds
     * @return weight factor between 0.0 and 1.0
     */
    private double calculatePreviousWindowWeight(long currentTimeMillis, long windowSizeMillis) {
        long timeIntoCurrentWindow = currentTimeMillis % windowSizeMillis;
        return (double) (windowSizeMillis - timeIntoCurrentWindow) / windowSizeMillis;
    }
    
    /**
     * Calculate the reset time (next window boundary).
     * 
     * @param currentWindow current window identifier
     * @param windowSizeMillis window size in milliseconds
     * @return reset time as Instant
     */
    private Instant calculateResetTime(long currentWindow, long windowSizeMillis) {
        long nextWindowStartMillis = (currentWindow + 1) * windowSizeMillis;
        return Instant.ofEpochMilli(nextWindowStartMillis);
    }
    
    /**
     * Build Redis key for current window counter.
     * 
     * @param key user-provided rate limit key
     * @return Redis key for current window
     */
    private String buildCurrentWindowKey(String key) {
        return KEY_PREFIX + key + CURRENT_SUFFIX;
    }
    
    /**
     * Build Redis key for previous window counter.
     * 
     * @param key user-provided rate limit key
     * @return Redis key for previous window
     */
    private String buildPreviousWindowKey(String key) {
        return KEY_PREFIX + key + PREVIOUS_SUFFIX;
    }
    
    /**
     * Load the Lua script from classpath resources.
     * 
     * @return Lua script content as string
     * @throws RuntimeException if script cannot be loaded
     */
    private String loadLuaScript() {
        try {
            ClassPathResource resource = new ClassPathResource("lua/sliding-window-counter.lua");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load sliding-window-counter.lua script", e);
        }
    }
    
    /**
     * Parse window string to milliseconds.
     * Supports: 1s, 30s, 1m, 5m, 1h, etc.
     * 
     * @param window window string (e.g., "60s", "5m")
     * @return window duration in milliseconds
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