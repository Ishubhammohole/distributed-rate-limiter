# Implementation Plan: Sliding Window Counter Algorithm

## Overview

This implementation plan provides step-by-step tasks to implement the Sliding Window Counter rate limiting algorithm. The implementation will integrate seamlessly with the existing strategy pattern architecture and maintain all performance and reliability guarantees.

## Maven Test Execution Guide

**Unit Tests (No Docker Required):**
```bash
./mvnw test
```
- Runs all unit tests including new SlidingWindowCounterRateLimiterStrategyTest
- Excludes integration tests tagged with `@Tag("integration")`
- Should complete successfully without Redis/Docker dependencies

**Integration Tests (Docker Required):**
```bash
./mvnw verify
```
- Runs unit tests AND integration tests
- Integration tests use Testcontainers for Redis
- If Docker unavailable, integration tests skip gracefully with `@Testcontainers(disabledWithoutDocker = true)`

## Tasks

### Task 1: Create Lua Script for Sliding Window Counter

- [ ] 1.1 Create `sliding-window-counter.lua` script in `src/main/resources/lua/`
  - Implement atomic counter operations for current and previous windows
  - Calculate weighted sum using previous window weight factor
  - Handle counter initialization and TTL management
  - Return structured response: [allowed, remaining, currentCount, previousCount]
  - _Requirements: 2.1, 2.2, 2.5, 5.1, 5.2, 5.3_

- [ ] 1.2 Add Lua script validation during application startup
  - Load and validate script syntax in configuration class
  - Cache script SHA for efficient execution
  - Log script loading success/failure
  - _Requirements: 5.5, 7.5_

### Task 2: Implement SlidingWindowCounterRateLimiterStrategy

- [ ] 2.1 Create strategy class implementing RateLimiterStrategy interface
  - Extend existing strategy pattern
  - Implement checkRateLimit method signature
  - Add constructor with required dependencies (RedisTemplate, MeterRegistry, Clock)
  - _Requirements: 4.1, 4.2_

- [ ] 2.2 Implement window calculation methods
  - `calculateCurrentWindow(long currentTimeMillis, long windowSizeMillis)`
  - `calculatePreviousWindowWeight(long currentTimeMillis, long windowSizeMillis)`
  - `calculateResetTime(long currentWindow, long windowSizeMillis)`
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ] 2.3 Implement Redis key building methods
  - `buildCurrentWindowKey(String key)` following pattern "rate_limit:sliding_window_counter:{key}:current"
  - `buildPreviousWindowKey(String key)` following pattern "rate_limit:sliding_window_counter:{key}:previous"
  - _Requirements: 2.1_

- [ ] 2.4 Implement Lua script execution logic
  - Execute script with proper parameter mapping
  - Handle script response parsing
  - Implement retry logic for script execution failures
  - _Requirements: 1.1, 5.1, 5.6_

- [ ] 2.5 Implement error handling and fail-open behavior
  - Handle Redis connection failures
  - Handle Lua script execution timeouts
  - Log errors with appropriate context
  - Return fail-open responses maintaining API contract
  - _Requirements: 2.4, 7.1, 7.2_

### Task 3: Add Strategy Registration and Configuration

- [ ] 3.1 Register strategy in RateLimiterService or StrategyFactory
  - Add "sliding_window_counter" algorithm mapping
  - Ensure strategy is available for algorithm selection
  - _Requirements: 4.4_

- [ ] 3.2 Add metrics integration
  - Implement algorithm-specific metrics with "sliding_window_counter" label
  - Add window transition metrics
  - Add fail-open metrics with reason codes
  - _Requirements: 4.5, 7.3, 7.5_

### Task 4: Create Unit Tests

- [ ] 4.1 Create SlidingWindowCounterRateLimiterStrategyTest class
  - Test normal operation within rate limits
  - Test rate limiting when threshold exceeded
  - Test window boundary calculations
  - Test previous window weighting logic
  - _Requirements: 6.1, 6.5_

- [ ] 4.2 Add edge case unit tests
  - Test clock skew handling
  - Test Redis failure scenarios with mocked RedisTemplate
  - Test invalid parameter handling
  - Test TTL calculation edge cases
  - _Requirements: 3.5, 6.5_

- [ ] 4.3 Add window transition unit tests
  - Test counter promotion from current to previous
  - Test reset time calculations across window boundaries
  - Test weight calculation accuracy
  - _Requirements: 1.5, 3.1, 6.3_

### Task 5: Create Integration Tests

- [ ] 5.1 Create SlidingWindowCounterIntegrationTest class
  - Add `@Tag("integration")` and `@Testcontainers(disabledWithoutDocker = true)`
  - Set up Redis container for testing
  - Test end-to-end rate limiting with real Redis
  - _Requirements: 6.2, 6.4_

- [ ] 5.2 Add concurrent access integration tests
  - Test multiple threads accessing same rate limit key
  - Verify atomic operations under concurrent load
  - Test distributed consistency across multiple strategy instances
  - _Requirements: 2.2, 2.5_

- [ ] 5.3 Add performance validation integration tests
  - Measure Redis operation latency
  - Validate throughput under load
  - Test memory usage patterns
  - _Requirements: 6.6, 8.3, 8.4_

### Task 6: Add Algorithm to API Validation

- [ ] 6.1 Update algorithm validation in request validation layer
  - Add "sliding_window_counter" to accepted algorithm values
  - Ensure validation error messages include new algorithm
  - _Requirements: 8.1, 8.2_

- [ ] 6.2 Update API documentation and examples
  - Add sliding window counter to algorithm descriptions
  - Include example requests and responses
  - Document memory efficiency benefits
  - _Requirements: 9.1, 9.2_

### Task 7: Testing and Validation

- [ ] 7.1 Run complete test suite
  - Execute `./mvnw test` to verify unit tests pass without Docker
  - Execute `./mvnw verify` to run integration tests with Docker
  - Verify all existing tests continue passing
  - _Requirements: 6.1, 6.2, 10.5_

- [ ] 7.2 Validate algorithm behavior
  - Test algorithm selection routes correctly to new strategy
  - Verify response format matches existing algorithms
  - Test fail-open behavior when Redis unavailable
  - _Requirements: 4.4, 10.1, 10.2_

- [ ] 7.3 Performance validation
  - Measure p99 latency under load
  - Verify memory usage is O(1) per key
  - Compare accuracy against sliding window log implementation
  - _Requirements: 8.3, 8.5, 8.6_

### Task 8: Documentation and Code Quality

- [ ] 8.1 Add comprehensive JavaDoc comments
  - Document algorithm logic and trade-offs
  - Explain window calculation formulas
  - Document error handling behavior
  - _Requirements: 9.1, 9.3_

- [ ] 8.2 Add inline code comments
  - Comment complex calculations in Lua script
  - Explain window weighting logic
  - Document Redis key schema decisions
  - _Requirements: 9.2, 9.4_

- [ ] 8.3 Update README.md algorithm comparison table
  - Add sliding window counter row with characteristics
  - Update "When to Use Each Algorithm" section
  - Verify all algorithm examples work
  - _Requirements: 9.5_

### Task 9: Final Integration and Cleanup

- [ ] 9.1 Verify backward compatibility
  - Ensure existing algorithms unchanged
  - Test API contract compatibility
  - Verify metrics don't conflict
  - _Requirements: 10.1, 10.2, 10.3_

- [ ] 9.2 Clean up temporary/debug code
  - Remove any debug logging
  - Clean up test data and temporary files
  - Verify code follows existing style conventions
  - _Requirements: 9.6_

- [ ] 9.3 Final validation checkpoint
  - Run full test suite one final time
  - Verify all requirements are met
  - Test deployment readiness
  - _Requirements: 10.5, 10.6_

## Risk Mitigation Checklist

- [ ] **Redis Key Conflicts**: Verify key schema doesn't conflict with existing algorithms
- [ ] **Performance Regression**: Validate new algorithm doesn't impact existing algorithm performance
- [ ] **Memory Leaks**: Confirm TTL settings prevent Redis memory growth
- [ ] **Clock Skew**: Test behavior with system clock changes
- [ ] **Concurrent Access**: Verify Lua script atomicity under high concurrency
- [ ] **Fail-Open Behavior**: Ensure consistent error handling across all failure modes

## Success Criteria

✅ **Functional Requirements:**
- Sliding window counter algorithm correctly implements time-weighted approximation
- Algorithm integrates seamlessly with existing strategy pattern
- Redis operations maintain atomicity through Lua scripts
- Error handling provides fail-open behavior consistent with other algorithms

✅ **Performance Requirements:**
- p99 latency remains <10ms including Redis operations
- Memory usage is O(1) per rate limit key
- Throughput supports 10,000+ RPS per instance
- Accuracy within 5% of exact sliding window calculation

✅ **Quality Requirements:**
- Unit tests run without Docker dependencies (`./mvnw test`)
- Integration tests run with Docker and skip gracefully without (`./mvnw verify`)
- All existing tests continue passing
- Code coverage maintains >90% for new implementation

✅ **Operational Requirements:**
- Comprehensive metrics for monitoring and alerting
- Detailed logging for troubleshooting
- Documentation sufficient for team maintenance
- Deployment can be rolled back safely if needed