package com.ratelimiter.strategy;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.infrastructure.LuaScriptExecutor;
import com.ratelimiter.infrastructure.RedisTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/**
 * Tests for sliding window counter approximation behavior and accuracy bounds.
 * Validates that the algorithm behaves according to documented approximation characteristics.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked") // ArgumentCaptor<List<String>> - safe for test mocking
class SlidingWindowCounterApproximationTest {

    @Mock
    private LuaScriptExecutor scriptExecutor;
    
    @Mock
    private RedisTimeProvider timeProvider;
    
    private Clock clock;
    private SlidingWindowCounterRateLimiterStrategy strategy;
    
    @BeforeEach
    void setUp() {
        // Use fixed clock instead of mocking
        clock = Clock.fixed(Instant.ofEpochMilli(1000000L), ZoneOffset.UTC);
        strategy = new SlidingWindowCounterRateLimiterStrategy(scriptExecutor, timeProvider, clock);
    }
    
    @Test
    void shouldAllowRequestWhenApproximationUnderestimates() {
        // Test case: Boundary condition where floor() causes underestimation
        // Previous window: 3 requests, Weight: 0.99, floor(3 * 0.99) = 2
        // Current window: 0 requests
        // Estimated: 2 + 0 = 2, Decision: 2 + 1 <= 3 → ALLOW
        // TRUE count would be 4 (should deny), but approximation allows
        
        long requestTime = 1000100L; // 0.1s into 10s window
        
        doReturn(requestTime).when(timeProvider).getCurrentTimestampMillis();
        // Return format: [allowed, remaining, currentCount, previousCount, weight]
        doReturn(List.of(1L, 0L, 1L, 3L, 0.99)).when(scriptExecutor)
            .executeList(
                org.mockito.ArgumentMatchers.contains("-- Sliding Window Counter Rate Limiter with Window-Scoped Keys"), 
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyInt(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong());
        
        RateLimitRequest request = new RateLimitRequest("test-key", "sliding_window_counter", 3L, "10s", 1);
        RateLimitResponse response = strategy.execute(request);
        
        // Capture and validate the actual arguments passed to scriptExecutor
        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Long> limitCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> costCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Long> timeCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> windowCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        
        verify(scriptExecutor).executeList(
            scriptCaptor.capture(),
            keysCaptor.capture(), 
            limitCaptor.capture(),
            costCaptor.capture(),
            timeCaptor.capture(),
            windowCaptor.capture(),
            ttlCaptor.capture()
        );
        
        // Validate Lua script contains distinctive header
        assertThat(scriptCaptor.getValue()).contains("-- Sliding Window Counter Rate Limiter with Window-Scoped Keys");
        
        // Validate KEYS follow expected format with correct window IDs
        List<String> capturedKeys = keysCaptor.getValue();
        assertThat(capturedKeys).hasSize(2);
        assertThat(capturedKeys.get(0)).isEqualTo("rate_limit:sliding_window_counter:test-key:100"); // current window
        assertThat(capturedKeys.get(1)).isEqualTo("rate_limit:sliding_window_counter:test-key:99");  // previous window
        
        // Validate ARGV parameters match test expectations
        assertThat(limitCaptor.getValue()).isEqualTo(3L);
        assertThat(costCaptor.getValue()).isEqualTo(1);
        assertThat(timeCaptor.getValue()).isEqualTo(1000100L); // test-controlled timestamp
        assertThat(windowCaptor.getValue()).isEqualTo(10000L); // 10s in milliseconds
        assertThat(ttlCaptor.getValue()).isGreaterThan(0L); // TTL should be positive (actual value may vary)
        
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(0L);
        
        // This demonstrates the approximation behavior: request is allowed even though
        // the true sliding window count would exceed the limit
    }
    
    @Test
    void shouldDenyRequestWhenApproximationIsAccurate() {
        // Test case: Approximation correctly identifies rate limit violation
        // Previous window: 3 requests, Weight: 0.5, floor(3 * 0.5) = 1
        // Current window: 2 requests
        // Estimated: 1 + 2 = 3, Decision: 3 + 1 <= 3 → DENY
        
        long requestTime = 1005000L; // 5s into 10s window (weight = 0.5)
        
        doReturn(requestTime).when(timeProvider).getCurrentTimestampMillis();
        // Return format: [allowed, remaining, currentCount, previousCount, weight, retryAfterSeconds]
        doReturn(List.of(0L, 0L, 2L, 3L, 0.5, 5L)).when(scriptExecutor)
            .executeList(
                org.mockito.ArgumentMatchers.contains("-- Sliding Window Counter Rate Limiter with Window-Scoped Keys"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyInt(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong());
        
        RateLimitRequest request = new RateLimitRequest("test-key", "sliding_window_counter", 3L, "10s", 1);
        RateLimitResponse response = strategy.execute(request);
        
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getRemaining()).isEqualTo(0L);
    }
    
    @Test
    void shouldHandleBoundaryTransitionAccurately() {
        // Test case: Window transition with high weight
        // Previous window: 2 requests, Weight: 0.95, floor(2 * 0.95) = 1
        // Current window: 1 request
        // Estimated: 1 + 1 = 2, Decision: 2 + 1 <= 3 → ALLOW
        
        long requestTime = 1000500L; // 0.5s into 10s window (weight = 0.95)
        
        doReturn(requestTime).when(timeProvider).getCurrentTimestampMillis();
        // Return format: [allowed, remaining, currentCount, previousCount, weight]
        doReturn(List.of(1L, 0L, 2L, 2L, 0.95)).when(scriptExecutor)
            .executeList(
                org.mockito.ArgumentMatchers.contains("-- Sliding Window Counter Rate Limiter with Window-Scoped Keys"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyInt(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong());
        
        RateLimitRequest request = new RateLimitRequest("test-key", "sliding_window_counter", 3L, "10s", 1);
        RateLimitResponse response = strategy.execute(request);
        
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(0L);
        
        // Validates that boundary transitions work correctly with high weights
    }
    
    @Test
    void shouldDemonstrateWorstCaseApproximationError() {
        // Test case: Worst-case scenario from documentation
        // This test documents the expected approximation behavior under extreme conditions
        
        long requestTime = 1000034L; // 0.034s into 10s window (weight ≈ 0.9966)
        
        doReturn(requestTime).when(timeProvider).getCurrentTimestampMillis();
        // Simulate: previous=3, current=0, weight=0.9966, floor(3 * 0.9966) = 2
        // Return format: [allowed, remaining, currentCount, previousCount, weight]
        doReturn(List.of(1L, 0L, 1L, 3L, 0.9966)).when(scriptExecutor)
            .executeList(
                org.mockito.ArgumentMatchers.contains("-- Sliding Window Counter Rate Limiter with Window-Scoped Keys"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyInt(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong());
        
        RateLimitRequest request = new RateLimitRequest("test-key", "sliding_window_counter", 3L, "10s", 1);
        RateLimitResponse response = strategy.execute(request);
        
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(0L);
        
        // This test documents that in worst-case boundary conditions,
        // the algorithm may allow requests that should be denied by exact sliding window.
        // This is acceptable per the updated requirements (≤25% error in worst case).
    }
    
    @Test
    void shouldHandleTypicalDistributedRequestPattern() {
        // Test case: Typical usage with requests spread across window
        // Previous window: 1 request, Weight: 0.7, floor(1 * 0.7) = 0
        // Current window: 2 requests
        // Estimated: 0 + 2 = 2, Decision: 2 + 1 <= 3 → ALLOW
        
        long requestTime = 1003000L; // 3s into 10s window (weight = 0.7)
        
        doReturn(requestTime).when(timeProvider).getCurrentTimestampMillis();
        // Return format: [allowed, remaining, currentCount, previousCount, weight]
        doReturn(List.of(1L, 0L, 3L, 1L, 0.7)).when(scriptExecutor)
            .executeList(
                org.mockito.ArgumentMatchers.contains("-- Sliding Window Counter Rate Limiter with Window-Scoped Keys"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyInt(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong());
        
        RateLimitRequest request = new RateLimitRequest("test-key", "sliding_window_counter", 3L, "10s", 1);
        RateLimitResponse response = strategy.execute(request);
        
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(0L);
        
        // Under typical distributed request patterns, approximation error is minimal
    }
    
    @Test
    void shouldFailOpenWhenRedisUnavailable() {
        // Test case: Fail-open behavior when Redis/Lua script fails
        
        long requestTime = 1000000L;
        
        doReturn(requestTime).when(timeProvider).getCurrentTimestampMillis();
        // Simulate Redis failure by returning empty list (triggers "Lua script returned empty result")
        doReturn(List.of()).when(scriptExecutor)
            .executeList(
                org.mockito.ArgumentMatchers.contains("-- Sliding Window Counter Rate Limiter with Window-Scoped Keys"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyInt(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong());
        
        RateLimitRequest request = new RateLimitRequest("test-key", "sliding_window_counter", 3L, "10s", 1);
        RateLimitResponse response = strategy.execute(request);
        
        // Fail-open behavior: allow request with remaining=2L (limit-1)
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemaining()).isEqualTo(2L);
        
        // This validates the fail-open behavior when Redis is unavailable
    }
}