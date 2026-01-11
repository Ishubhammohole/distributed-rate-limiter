package com.ratelimiter.strategy;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.infrastructure.LuaScriptExecutor;
import com.ratelimiter.infrastructure.RedisTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FixedWindowRateLimiterStrategyTest {

    @Mock
    private LuaScriptExecutor scriptExecutor;

    @Mock
    private RedisTimeProvider timeProvider;

    private FixedWindowRateLimiterStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new FixedWindowRateLimiterStrategy(scriptExecutor, timeProvider);
    }

    @Test
    void getAlgorithm_ReturnsFixedWindow() {
        assertThat(strategy.getAlgorithm()).isEqualTo(RateLimitAlgorithm.FIXED_WINDOW);
    }

    @Test
    void execute_AllowedRequest_ReturnsAllowedResponse() {
        // Arrange
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 1);
        long currentTime = 1609459200000L; // 2021-01-01 00:00:00 UTC
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        when(scriptExecutor.executeList(anyString(), anyList(), any(), any(), any(), any()))
            .thenReturn(List.of(1L, 9L, currentTime + 60000L)); // allowed=1, remaining=9, reset in 60s
        
        // Act
        RateLimitResponse response = strategy.execute(request);
        
        // Assert
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(9);
        assertThat(response.getResetTime()).isEqualTo(Instant.ofEpochMilli(currentTime + 60000L));
    }

    @Test
    void execute_DeniedRequest_ReturnsDeniedResponse() {
        // Arrange
        RateLimitRequest request = createRequest("test-key", 5L, "60s", 1);
        long currentTime = 1609459200000L;
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        when(scriptExecutor.executeList(anyString(), anyList(), any(), any(), any(), any()))
            .thenReturn(List.of(0L, 0L, currentTime + 60000L)); // allowed=0, remaining=0, reset in 60s
        
        // Act
        RateLimitResponse response = strategy.execute(request);
        
        // Assert
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getRemaining()).isEqualTo(0);
        assertThat(response.getResetTime()).isEqualTo(Instant.ofEpochMilli(currentTime + 60000L));
    }

    @Test
    void execute_HighCostRequest_HandlesCorrectly() {
        // Arrange
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 5);
        long currentTime = 1609459200000L;
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        when(scriptExecutor.executeList(anyString(), anyList(), any(), any(), any(), any()))
            .thenReturn(List.of(1L, 5L, currentTime + 60000L)); // allowed=1, remaining=5, reset in 60s
        
        // Act
        RateLimitResponse response = strategy.execute(request);
        
        // Assert
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(5);
    }

    @Test
    void execute_DifferentWindowSizes_ParsedCorrectly() {
        // Test different window formats
        String[] windows = {"1s", "30s", "5m", "2h"};
        long[] expectedMs = {1000L, 30000L, 300000L, 7200000L};
        
        for (int i = 0; i < windows.length; i++) {
            RateLimitRequest request = createRequest("test-key-" + i, 10L, windows[i], 1);
            long currentTime = 1609459200000L;
            
            when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
            when(scriptExecutor.executeList(anyString(), anyList(), 
                eq(currentTime), eq(expectedMs[i]), eq(10L), eq(1)))
                .thenReturn(List.of(1L, 9L, currentTime + expectedMs[i]));
            
            RateLimitResponse response = strategy.execute(request);
            
            assertThat(response.isAllowed()).isTrue();
            assertThat(response.getRemaining()).isEqualTo(9);
        }
    }

    @Test
    void execute_ScriptExecutionFails_ThrowsRuntimeException() {
        // Arrange
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 1);
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(1609459200000L);
        when(scriptExecutor.executeList(anyString(), anyList(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Redis connection failed"));
        
        // Act & Assert
        assertThatThrownBy(() -> strategy.execute(request))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("Fixed window rate limiting failed");
    }

    @Test
    void validateRequest_NullRequest_ThrowsException() {
        assertThatThrownBy(() -> strategy.validateRequest(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Request cannot be null");
    }

    @Test
    void validateRequest_NullKey_ThrowsException() {
        RateLimitRequest request = createRequest(null, 10L, "60s", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Key cannot be null or empty");
    }

    @Test
    void validateRequest_EmptyKey_ThrowsException() {
        RateLimitRequest request = createRequest("", 10L, "60s", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Key cannot be null or empty");
    }

    @Test
    void validateRequest_WhitespaceKey_ThrowsException() {
        RateLimitRequest request = createRequest("   ", 10L, "60s", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Key cannot be null or empty");
    }

    @Test
    void validateRequest_NullLimit_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", null, "60s", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Limit must be positive");
    }

    @Test
    void validateRequest_ZeroLimit_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", 0L, "60s", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Limit must be positive");
    }

    @Test
    void validateRequest_NegativeLimit_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", -5L, "60s", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Limit must be positive");
    }

    @Test
    void validateRequest_ZeroCost_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 0);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Cost must be positive");
    }

    @Test
    void validateRequest_NegativeCost_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", 10L, "60s", -1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Cost must be positive");
    }

    @Test
    void validateRequest_NullWindow_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", 10L, null, 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Window cannot be null or empty");
    }

    @Test
    void validateRequest_EmptyWindow_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", 10L, "", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Window cannot be null or empty");
    }

    @Test
    void validateRequest_InvalidWindowFormat_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", 10L, "invalid", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid window format: invalid");
    }

    @Test
    void validateRequest_InvalidWindowUnit_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", 10L, "60x", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid window format: 60x");
    }

    @Test
    void validateRequest_InvalidWindowValue_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", 10L, "abcs", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid window format: abcs");
    }

    @Test
    void validateRequest_ZeroWindowValue_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", 10L, "0s", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid window format: 0s");
    }

    @Test
    void validateRequest_NegativeWindowValue_ThrowsException() {
        RateLimitRequest request = createRequest("test-key", 10L, "-5s", 1);
        
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid window format: -5s");
    }

    @Test
    void validateRequest_ValidRequest_DoesNotThrow() {
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 1);
        
        // Should not throw any exception
        strategy.validateRequest(request);
    }

    @Test
    void validateRequest_SupportedWindowUnits_DoesNotThrow() {
        String[] validWindows = {"1s", "30s", "5m", "2h", "1d"};
        
        for (String window : validWindows) {
            RateLimitRequest request = createRequest("test-key", 10L, window, 1);
            
            // Should not throw any exception
            strategy.validateRequest(request);
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