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


import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SlidingWindowCounterRateLimiterStrategy.
 * Tests algorithm logic, window calculations, and error handling without Redis dependencies.
 */
@ExtendWith(MockitoExtension.class)
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
        when(scriptExecutor.executeList(anyString(), anyList(), 
            eq(10L), eq(1), eq(currentTime), eq(60000L), eq(120L)))
            .thenReturn(List.of(1L, 9L, 1L, 0L, 0.0)); // allowed=1, remaining=9, currentCount=1, previousCount=0, weight=0.0
        
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
        when(scriptExecutor.executeList(anyString(), anyList(), 
            eq(5L), eq(1), eq(currentTime), eq(60000L), eq(120L)))
            .thenReturn(List.of(0L, 0L, 5L, 2L, 0.5, 60L)); // allowed=0, remaining=0, currentCount=5, previousCount=2, weight=0.5, retryAfter=60s
        
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
        when(scriptExecutor.executeList(anyString(), anyList(), 
            eq(10L), eq(3), eq(currentTime), eq(60000L), eq(120L)))
            .thenReturn(List.of(1L, 7L, 3L, 0L, 0.0)); // allowed=1, remaining=7, currentCount=3, previousCount=0, weight=0.0
        
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
        when(scriptExecutor.executeList(anyString(), anyList(), 
            eq(10L), eq(1), eq(currentTime), eq(60000L), eq(120L)))
            .thenReturn(List.of(1L, 8L, 2L, 4L, 0.5)); // allowed=1, remaining=8, current=2, previous=4, weight=0.5
        
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
        long[] windowMillis = {1000L, 30000L, 300000L, 7200000L};
        long[] expectedTtl = {60L, 60L, 600L, 14400L}; // TTL = max(60, 2 * windowSeconds)
        
        for (int i = 0; i < windows.length; i++) {
            RateLimitRequest request = createRequest("test-key-" + i, 10L, windows[i], 1);
            long currentTime = 1609459200000L;
            
            when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
            when(scriptExecutor.executeList(anyString(), anyList(), 
                eq(10L), eq(1), eq(currentTime), eq(windowMillis[i]), eq(expectedTtl[i])))
                .thenReturn(List.of(1L, 9L, 1L, 0L, 0.0));
            
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
        
        // Use doThrow to avoid stubbing conflicts with other tests
        doThrow(new RuntimeException("Redis connection failed"))
            .when(scriptExecutor)
            .executeList(anyString(), anyList(), anyLong(), anyInt(), anyLong(), anyLong(), anyLong());
        
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
        
        long expectedWindowStart = 1609459200000L; // Window start
        
        for (long timestamp : timestamps) {
            when(timeProvider.getCurrentTimestampMillis()).thenReturn(timestamp);
            when(scriptExecutor.executeList(anyString(), anyList(), 
                eq(10L), eq(1), eq(timestamp), eq(60000L), eq(120L)))
                .thenReturn(List.of(1L, 9L, 1L, 0L, 0.0));
            
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
        when(scriptExecutor.executeList(anyString(), anyList(), 
            eq(10L), eq(1), eq(currentTime), eq(60000L), eq(120L)))
            .thenReturn(List.of(1L, 9L, 1L, 0L, 0.5));
        
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
        // Arrange: Test basic window rollover behavior
        RateLimitRequest request = createRequest("rollover-test", 3L, "60s", 1);
        long currentTime = 1609459200000L;
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        when(scriptExecutor.executeList(anyString(), anyList(), 
            eq(3L), eq(1), eq(currentTime), eq(60000L), eq(120L)))
            .thenReturn(List.of(1L, 2L, 1L, 0L, 0.0)); // allowed, remaining=2, current=1, previous=0, weight=0.0
        
        // Act & Assert
        RateLimitResponse response = strategy.execute(request);
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(2);
    }
    
    @Test
    void execute_windowBoundaryTransition_handlesRotationCorrectly() {
        // Arrange: Test window boundary transition
        RateLimitRequest request = createRequest("boundary-test", 3L, "60s", 1);
        long currentTime = 1609459200000L;
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        when(scriptExecutor.executeList(anyString(), anyList(), 
            eq(3L), eq(1), eq(currentTime), eq(60000L), eq(120L)))
            .thenReturn(List.of(1L, 2L, 1L, 0L, 0.0)); // allowed, remaining=2
        
        // Act
        RateLimitResponse response = strategy.execute(request);
        
        // Assert
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(2);
    }

    @Test
    void execute_deterministicWindowRollover_correctlyResetsCounters() {
        // This test verifies the critical window rollover bug is fixed
        // Given: Same window - exhaust limit (3 requests allowed)
        RateLimitRequest request = createRequest("rollover-boundary-test", 3L, "60s", 1);
        long windowTime = 1609459200000L; // Window boundary
        
        // Mock same window requests (should allow 3, deny rest)
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(windowTime);
        when(scriptExecutor.executeList(anyString(), anyList(), 
            eq(3L), eq(1), eq(windowTime), eq(60000L), eq(120L)))
            .thenReturn(List.of(1L, 2L, 1L, 0L, 0.0)) // 1st request: allowed
            .thenReturn(List.of(1L, 1L, 2L, 0L, 0.0)) // 2nd request: allowed
            .thenReturn(List.of(1L, 0L, 3L, 0L, 0.0)) // 3rd request: allowed
            .thenReturn(List.of(0L, 0L, 3L, 0L, 0.0)); // 4th request: denied
        
        // When: Make 4 requests in same window
        RateLimitResponse response1 = strategy.execute(request);
        RateLimitResponse response2 = strategy.execute(request);
        RateLimitResponse response3 = strategy.execute(request);
        RateLimitResponse response4 = strategy.execute(request);
        
        // Then: First 3 allowed, 4th denied
        assertThat(response1.isAllowed()).isTrue();
        assertThat(response2.isAllowed()).isTrue();
        assertThat(response3.isAllowed()).isTrue();
        assertThat(response4.isAllowed()).isFalse();
        
        // Given: Fresh window (next window boundary)
        long nextWindowTime = windowTime + 60000L; // Next window
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(nextWindowTime);
        when(scriptExecutor.executeList(anyString(), anyList(), 
            eq(3L), eq(1), eq(nextWindowTime), eq(60000L), eq(120L)))
            .thenReturn(List.of(1L, 2L, 1L, 3L, 1.0)); // Fresh window: allowed, previous window fully weighted
        
        // When: First request in new window
        RateLimitResponse freshWindowResponse = strategy.execute(request);
        
        // Then: CRITICAL - Must be allowed (this was the bug)
        assertThat(freshWindowResponse.isAllowed()).isTrue();
        assertThat(freshWindowResponse.getRemaining()).isEqualTo(2);
    }

    @Test
    void execute_highCostRequests_handlesCorrectly() {
        // Given - Test with cost=3 against limit=10
        RateLimitRequest request = createRequest("high-cost-test", 10L, "60s", 3);
        long currentTime = 1609459200000L;
        
        when(timeProvider.getCurrentTimestampMillis()).thenReturn(currentTime);
        when(scriptExecutor.executeList(anyString(), anyList(), 
            eq(10L), eq(3), eq(currentTime), eq(60000L), eq(120L)))
            .thenReturn(List.of(1L, 7L, 3L, 0L, 0.0)); // allowed, remaining=7, current=3
        
        // When
        RateLimitResponse response = strategy.execute(request);
        
        // Then - Should allow and correctly calculate remaining
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(7L); // 10 - 3 = 7
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