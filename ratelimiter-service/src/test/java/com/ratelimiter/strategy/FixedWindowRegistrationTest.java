package com.ratelimiter.strategy;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.infrastructure.LuaScriptExecutor;
import com.ratelimiter.infrastructure.RedisTimeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple test to verify Fixed Window strategy registration and basic functionality
 * without requiring Redis/Docker.
 */
@ExtendWith(MockitoExtension.class)
class FixedWindowRegistrationTest {

    @Mock
    private LuaScriptExecutor scriptExecutor;

    @Mock
    private RedisTimeProvider timeProvider;

    @Test
    void fixedWindowStrategy_CanBeInstantiated() {
        FixedWindowRateLimiterStrategy strategy = new FixedWindowRateLimiterStrategy(scriptExecutor, timeProvider);
        
        assertThat(strategy).isNotNull();
        assertThat(strategy.getAlgorithm()).isEqualTo(RateLimitAlgorithm.FIXED_WINDOW);
    }

    @Test
    void fixedWindowStrategy_ValidatesRequests() {
        FixedWindowRateLimiterStrategy strategy = new FixedWindowRateLimiterStrategy(scriptExecutor, timeProvider);
        
        // Valid request should not throw
        RateLimitRequest validRequest = createRequest("test-key", 10L, "60s", 1);
        strategy.validateRequest(validRequest);
        
        // Invalid request should throw
        RateLimitRequest invalidRequest = createRequest(null, 10L, "60s", 1);
        
        try {
            strategy.validateRequest(invalidRequest);
            assertThat(false).as("Should have thrown exception for null key").isTrue();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Key cannot be null or empty");
        }
    }

    @Test
    void fixedWindowStrategy_ParsesWindowFormats() {
        FixedWindowRateLimiterStrategy strategy = new FixedWindowRateLimiterStrategy(scriptExecutor, timeProvider);
        
        // Test that validation passes for various window formats
        String[] validWindows = {"1s", "30s", "5m", "2h", "1d"};
        
        for (String window : validWindows) {
            RateLimitRequest request = createRequest("test-key", 10L, window, 1);
            strategy.validateRequest(request); // Should not throw
        }
    }

    private RateLimitRequest createRequest(String key, Long limit, String window, int cost) {
        RateLimitRequest request = new RateLimitRequest();
        request.setKey(key);
        request.setAlgorithm("fixed_window");
        request.setLimit(limit);
        request.setWindow(window);
        request.setCost(cost);
        return request;
    }
}