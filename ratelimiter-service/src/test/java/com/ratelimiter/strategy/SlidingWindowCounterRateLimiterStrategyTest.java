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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SlidingWindowCounterRateLimiterStrategy.
 * Tests algorithm logic, window calculations, and error handling without Redis dependencies.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlidingWindowCounterRateLimiterStrategyTest {

    @Mock
    private LuaScriptExecutor scriptExecutor;

    @Mock
    private RedisTimeProvider timeProvider;

    @Mock
    private Clock clock;

    private SlidingWindowCounterRateLimiterStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SlidingWindowCounterRateLimiterStrategy(scriptExecutor, timeProvider, clock);
    }

    @Test
    void getAlgorithm_shouldReturnSlidingWindowCounter() {
        // When/Then
        assertThat(strategy.getAlgorithm()).isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW_COUNTER);
    }

    @Test
    void execute_allowedRequest_returnsAllowedResponse() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 1);
        long currentTime = 1609459200000L; // 2021-01-01 00:00:00 UTC
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        when(scriptExecutor.executeList(anyString(), anyList(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of(1L, 9L, 1L, 0L)); // allowed=1, remaining=9, currentCount=1, previousCount=0
        
        // When
        RateLimitResponse response = strategy.execute(request);
        
        // Then
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(9);
        assertThat(response.getResetTime()).isAfter(Instant.ofEpochMilli(currentTime));
    }

    @Test
    void execute_deniedRequest_returnsDeniedResponse() {
        // Given
        RateLimitRequest request = createRequest("test-key", 5L, "60s", 1);
        long currentTime = 1609459200000L;
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        when(scriptExecutor.executeList(anyString(), anyList(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of(0L, 0L, 5L, 2L)); // allowed=0, remaining=0, currentCount=5, previousCount=2
        
        // When
        RateLimitResponse response = strategy.execute(request);
        
        // Then
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getRemaining()).isEqualTo(0);
        assertThat(response.getResetTime()).isAfter(Instant.ofEpochMilli(currentTime));
    }

    @Test
    void execute_highCostRequest_handlesCorrectly() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 3);
        long currentTime = 1609459200000L;
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        when(scriptExecutor.executeList(anyString(), anyList(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of(1L, 7L, 3L, 0L)); // allowed=1, remaining=7, currentCount=3, previousCount=0
        
        // When
        RateLimitResponse response = strategy.execute(request);
        
        // Then
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(7);
    }

    @Test
    void execute_windowTransition_calculatesWeightCorrectly() {
        // Given - 30 seconds into a 60-second window (50% weight for previous window)
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 1);
        long windowStart = 1609459200000L; // Window boundary
        long currentTime = windowStart + 30000L; // 30 seconds into window
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        
        // Capture the arguments to verify weight calculation
        when(scriptExecutor.executeList(
            anyString(), 
            anyList(), 
            eq(10L), // limit
            eq(1), // cost
            eq(26824320L), // current window (currentTime / 60000)
            eq(26824319L), // previous window
            eq(0.5), // previous window weight (30s remaining / 60s total = 0.5)
            eq(120L) // TTL (2 * 60s)
        )).thenReturn(List.of(1L, 8L, 2L, 4L)); // allowed=1, remaining=8, current=2, previous=4
        
        // When
        RateLimitResponse response = strategy.execute(request);
        
        // Then
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(8);
    }

    @Test
    void execute_differentWindowSizes_parsedCorrectly() {
        // Test different window formats and their TTL calculations
        String[] windows = {"1s", "30s", "5m", "2h"};
        long[] expectedTtl = {60L, 60L, 600L, 14400L}; // TTL = max(60, 2 * windowSeconds)
        
        for (int i = 0; i < windows.length; i++) {
            RateLimitRequest request = createRequest("test-key-" + i, 10L, windows[i], 1);
            long currentTime = 1609459200000L;
            
            when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
            when(scriptExecutor.executeList(anyString(), anyList(), 
                eq(10L), eq(1), any(), any(), any(), eq(expectedTtl[i])))
                .thenReturn(List.of(1L, 9L, 1L, 0L));
            
            RateLimitResponse response = strategy.execute(request);
            
            assertThat(response.isAllowed()).isTrue();
            assertThat(response.getRemaining()).isEqualTo(9);
        }
    }

    @Test
    void execute_scriptExecutionFails_failsOpen() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 1);
        long currentTime = 1609459200000L;
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        when(clock.instant()).thenReturn(Instant.ofEpochMilli(currentTime));
        when(scriptExecutor.executeList(anyString(), anyList(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Redis connection failed"));
        
        // When
        RateLimitResponse response = strategy.execute(request);
        
        // Then - Should fail open (allow request)
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(9); // Conservative estimate (limit - 1)
        assertThat(response.getResetTime()).isAfter(Instant.ofEpochMilli(currentTime));
    }

    @Test
    void execute_redisTimeProviderFails_failsOpen() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 1);
        long currentTime = 1609459200000L;
        
        when(timeProvider.getCurrentTimestampMillis()).thenThrow(new RuntimeException("Redis time failed"));
        when(clock.instant()).thenReturn(Instant.ofEpochMilli(currentTime));
        
        // When
        RateLimitResponse response = strategy.execute(request);
        
        // Then - Should fail open
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(9);
    }

    @Test
    void execute_windowBoundaryCalculation_alignsToEpoch() {
        // Given - Test that windows align to epoch boundaries
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 1);
        
        // Test various timestamps that should map to the same window
        long[] timestamps = {
            1609459200000L, // 2021-01-01 00:00:00 (window boundary)
            1609459230000L, // 2021-01-01 00:00:30 (30s into window)
            1609459259999L  // 2021-01-01 00:00:59.999 (end of window)
        };
        
        long expectedWindow = 26824320L; // 1609459200000 / 60000
        
        for (long timestamp : timestamps) {
            when(timeProvider.getCurrentTimestampMillis()).thenReturn(timestamp);
            when(scriptExecutor.executeList(anyString(), anyList(), 
                any(), any(), eq(expectedWindow), any(), any(), any()))
                .thenReturn(List.of(1L, 9L, 1L, 0L));
            
            RateLimitResponse response = strategy.execute(request);
            assertThat(response.isAllowed()).isTrue();
        }
    }

    @Test
    void execute_resetTimeCalculation_pointsToNextWindow() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 1);
        long currentTime = 1609459230000L; // 30 seconds into window
        long expectedResetTime = 1609459260000L; // Next window boundary
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        when(scriptExecutor.executeList(anyString(), anyList(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of(1L, 9L, 1L, 0L));
        
        // When
        RateLimitResponse response = strategy.execute(request);
        
        // Then
        assertThat(response.getResetTime()).isEqualTo(Instant.ofEpochMilli(expectedResetTime));
    }

    @Test
    void validateRequest_nullRequest_throwsException() {
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Request cannot be null");
    }

    @Test
    void validateRequest_nullKey_throwsException() {
        // Given
        RateLimitRequest request = createRequest(null, 10L, "60s", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Key cannot be null or empty");
    }

    @Test
    void validateRequest_emptyKey_throwsException() {
        // Given
        RateLimitRequest request = createRequest("", 10L, "60s", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Key cannot be null or empty");
    }

    @Test
    void validateRequest_whitespaceKey_throwsException() {
        // Given
        RateLimitRequest request = createRequest("   ", 10L, "60s", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Key cannot be null or empty");
    }

    @Test
    void validateRequest_nullLimit_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", null, "60s", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Limit must be positive");
    }

    @Test
    void validateRequest_zeroLimit_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", 0L, "60s", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Limit must be positive");
    }

    @Test
    void validateRequest_negativeLimit_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", -5L, "60s", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Limit must be positive");
    }

    @Test
    void validateRequest_zeroCost_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 0);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Cost must be positive");
    }

    @Test
    void validateRequest_negativeCost_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "60s", -1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Cost must be positive");
    }

    @Test
    void validateRequest_nullWindow_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, null, 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Window cannot be null or empty");
    }

    @Test
    void validateRequest_emptyWindow_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Window cannot be null or empty");
    }

    @Test
    void validateRequest_invalidWindowFormat_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "invalid", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid window format: invalid");
    }

    @Test
    void validateRequest_invalidWindowUnit_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "60x", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid window format: 60x");
    }

    @Test
    void validateRequest_invalidWindowValue_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "abcs", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid window format: abcs");
    }

    @Test
    void validateRequest_zeroWindowValue_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "0s", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid window format: 0s");
    }

    @Test
    void validateRequest_negativeWindowValue_throwsException() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "-5s", 1);
        
        // When/Then
        assertThatThrownBy(() -> strategy.validateRequest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid window format: -5s");
    }

    @Test
    void validateRequest_validRequest_doesNotThrow() {
        // Given
        RateLimitRequest request = createRequest("test-key", 10L, "60s", 1);
        
        // When/Then - Should not throw any exception
        strategy.validateRequest(request);
    }

    @Test
    void validateRequest_supportedWindowUnits_doesNotThrow() {
        // Given
        String[] validWindows = {"1s", "30s", "5m", "2h", "1d"};
        
        for (String window : validWindows) {
            RateLimitRequest request = createRequest("test-key", 10L, window, 1);
            
            // When/Then - Should not throw any exception
            strategy.validateRequest(request);
        }
    }

    @Test
    void execute_windowRollover_allowsRequestsInNewWindow() {
        // Arrange: Set up mock time provider for controlled time progression
        long windowSizeMillis = 60000L; // 60 seconds
        long windowStart = 1000000L; // First window starts at 1000000ms
        
        when(timeProvider.getCurrentTimestampMillis())
            .thenReturn(windowStart + 1000L) // First requests in window 1
            .thenReturn(windowStart + 2000L)
            .thenReturn(windowStart + 3000L)
            .thenReturn(windowStart + windowSizeMillis + 1000L); // Fourth request in window 2
        
        // Mock Lua script responses for window 1 (fill up)
        when(scriptExecutor.executeList(anyString(), anyList(), eq(3L), eq(1), 
            eq(windowStart + 1000L), eq(windowSizeMillis), 
            eq(windowStart), eq(windowStart - windowSizeMillis), eq(120L)))
            .thenReturn(List.of(1L, 2L, 1L, 0L, 1.0)); // allowed, remaining=2, current=1, previous=0, weight=1.0
            
        when(scriptExecutor.executeList(anyString(), anyList(), eq(3L), eq(1), 
            eq(windowStart + 2000L), eq(windowSizeMillis), 
            eq(windowStart), eq(windowStart - windowSizeMillis), eq(120L)))
            .thenReturn(List.of(1L, 1L, 2L, 0L, 1.0)); // allowed, remaining=1, current=2, previous=0, weight=1.0
            
        when(scriptExecutor.executeList(anyString(), anyList(), eq(3L), eq(1), 
            eq(windowStart + 3000L), eq(windowSizeMillis), 
            eq(windowStart), eq(windowStart - windowSizeMillis), eq(120L)))
            .thenReturn(List.of(1L, 0L, 3L, 0L, 1.0)); // allowed, remaining=0, current=3, previous=0, weight=1.0
        
        // Mock Lua script response for window 2 (should be allowed due to sliding window)
        when(scriptExecutor.executeList(anyString(), anyList(), eq(3L), eq(1), 
            eq(windowStart + windowSizeMillis + 1000L), eq(windowSizeMillis), 
            eq(windowStart + windowSizeMillis), eq(windowStart), eq(120L)))
            .thenReturn(List.of(1L, 1L, 1L, 3L, 0.98)); // allowed, remaining=1, current=1, previous=3, weight=0.98
        
        RateLimitRequest request = createRequest("rollover-test", 3L, "60s", 1);
        
        // Act & Assert: Fill up first window
        RateLimitResponse response1 = strategy.execute(request);
        assertThat(response1.isAllowed()).isTrue();
        assertThat(response1.getRemaining()).isEqualTo(2);
        
        RateLimitResponse response2 = strategy.execute(request);
        assertThat(response2.isAllowed()).isTrue();
        assertThat(response2.getRemaining()).isEqualTo(1);
        
        RateLimitResponse response3 = strategy.execute(request);
        assertThat(response3.isAllowed()).isTrue();
        assertThat(response3.getRemaining()).isEqualTo(0);
        
        // Act: Request in new window should be allowed (with sliding window weighting)
        RateLimitResponse response4 = strategy.execute(request);
        assertThat(response4.isAllowed()).isTrue();
        assertThat(response4.getRemaining()).isEqualTo(1);
    }
    
    @Test
    void execute_windowBoundaryTransition_handlesRotationCorrectly() {
        // Arrange: Test exact window boundary transition
        long windowSizeMillis = 60000L;
        long windowStart = 2000000L;
        
        // Fill up window completely then test new window
        when(timeProvider.getCurrentTimestampMillis())
            .thenReturn(windowStart + 1000L)
            .thenReturn(windowStart + 2000L)
            .thenReturn(windowStart + 3000L)
            .thenReturn(windowStart + 4000L) // Should be denied
            .thenReturn(windowStart + windowSizeMillis + 100L); // New window, should be allowed
        
        // Mock responses for filling window
        when(scriptExecutor.executeList(anyString(), anyList(), eq(3L), eq(1), 
            eq(windowStart + 1000L), eq(windowSizeMillis), anyLong(), anyLong(), eq(120L)))
            .thenReturn(List.of(1L, 2L, 1L, 0L, 1.0));
            
        when(scriptExecutor.executeList(anyString(), anyList(), eq(3L), eq(1), 
            eq(windowStart + 2000L), eq(windowSizeMillis), anyLong(), anyLong(), eq(120L)))
            .thenReturn(List.of(1L, 1L, 2L, 0L, 1.0));
            
        when(scriptExecutor.executeList(anyString(), anyList(), eq(3L), eq(1), 
            eq(windowStart + 3000L), eq(windowSizeMillis), anyLong(), anyLong(), eq(120L)))
            .thenReturn(List.of(1L, 0L, 3L, 0L, 1.0));
        
        // Mock response for denied request
        when(scriptExecutor.executeList(anyString(), anyList(), eq(3L), eq(1), 
            eq(windowStart + 4000L), eq(windowSizeMillis), anyLong(), anyLong(), eq(120L)))
            .thenReturn(List.of(0L, 0L, 3L, 0L, 1.0, 56L)); // denied, remaining=0, retryAfter=56s
        
        // Mock response for new window request
        when(scriptExecutor.executeList(anyString(), anyList(), eq(3L), eq(1), 
            eq(windowStart + windowSizeMillis + 100L), eq(windowSizeMillis), anyLong(), anyLong(), eq(120L)))
            .thenReturn(List.of(1L, 2L, 1L, 3L, 0.998)); // allowed, remaining=2, current=1, previous=3, weight=0.998
        
        RateLimitRequest request = createRequest("boundary-test", 3L, "60s", 1);
        
        // Act: Fill window and get denied
        strategy.execute(request); // allowed
        strategy.execute(request); // allowed  
        strategy.execute(request); // allowed
        
        RateLimitResponse deniedResponse = strategy.execute(request);
        assertThat(deniedResponse.isAllowed()).isFalse();
        
        // Act: Request right after window boundary
        RateLimitResponse newWindowResponse = strategy.execute(request);
        assertThat(newWindowResponse.isAllowed()).isTrue();
        assertThat(newWindowResponse.getRemaining()).isEqualTo(2);
    }

    private RateLimitRequest createRequest(String key, Long limit, String window, int cost) {
        RateLimitRequest request = new RateLimitRequest();
        request.setKey(key);
        request.setAlgorithm("sliding_window_counter");
        request.setLimit(limit);
        request.setWindow(window);
        request.setCost(cost);
        return request;
    }
}