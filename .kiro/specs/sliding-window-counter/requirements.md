# Requirements Document: Sliding Window Counter Algorithm Implementation

## Introduction

This specification defines the requirements for implementing the Sliding Window Counter rate limiting algorithm to complete the distributed rate limiter service. This algorithm provides memory-efficient approximate rate limiting by combining current and previous window counters with time-based weighting. The implementation must integrate seamlessly with the existing Redis-backed architecture while maintaining atomic operations and performance guarantees.

## Current Implementation Status

**✅ IMPLEMENTED ALGORITHMS:**
- Token Bucket (`TokenBucketRateLimiterStrategy.java`)
- Sliding Window Log (`SlidingWindowLogRateLimiterStrategy.java`) 
- Fixed Window (`FixedWindowRateLimiterStrategy.java`)

**🎯 TARGET ALGORITHM:**
- Sliding Window Counter (this specification)

**✅ INFRASTRUCTURE COMPLETE:**
- Redis integration with Lua scripts for atomicity
- Strategy pattern architecture (`RateLimiterStrategy` interface)
- Comprehensive test separation (unit tests run without Docker, integration tests with `@Tag("integration")`)
- Metrics and observability integration
- API contract and validation layer

## Non-Goals and Out-of-Scope

❌ **Explicitly Out-of-Scope:**
- Modifying existing algorithm implementations (Token Bucket, Sliding Window Log, Fixed Window)
- Changing the API contract or response format
- Altering the Redis connection or Lua script execution framework
- Modifying the strategy registration or selection mechanism
- Performance optimization of existing algorithms
- Adding new algorithms beyond Sliding Window Counter

## Requirements

### Requirement 1: Sliding Window Counter Algorithm Core Logic

**User Story:** As an API consumer needing memory-efficient approximate rate limiting, I want sliding window counter algorithm to balance accuracy with resource usage, so that I can achieve effective rate limiting without excessive memory consumption.

#### Acceptance Criteria

1. WHEN a rate limit check is requested with algorithm "sliding_window_counter" THEN the system SHALL execute Redis Lua script combining current and previous window data
2. WHEN calculating current rate THEN the system SHALL weight previous window count based on time overlap with current window
3. WHEN the weighted count plus current window count exceeds the limit THEN the system SHALL return allowed=false with calculated resetTime
4. WHEN the weighted count plus current window count is within limits THEN the system SHALL increment current window counter and return allowed=true with accurate remaining count
5. IF window counters do not exist THEN the system SHALL initialize current window counter with value equal to request cost
6. WHEN transitioning between windows THEN the system SHALL maintain both current and previous window counters with appropriate TTLs

### Requirement 2: Redis Key Schema and Atomicity

**User Story:** As a system architect, I want consistent Redis key management and atomic operations, so that the sliding window counter maintains data integrity across distributed instances.

#### Acceptance Criteria

1. WHEN storing window counters THEN the system SHALL use Redis keys following pattern "rate_limit:sliding_window_counter:{key}:current" and "rate_limit:sliding_window_counter:{key}:previous"
2. WHEN executing rate limit checks THEN the system SHALL perform all Redis operations atomically within a single Lua script
3. WHEN setting TTL on window counters THEN the system SHALL set expiration to 2x window duration to ensure previous window availability
4. IF Redis operations fail THEN the system SHALL implement fail-open behavior consistent with existing algorithms
5. WHEN window boundaries are crossed THEN the system SHALL atomically promote current to previous and reset current counter
6. WHEN multiple costs are applied THEN the system SHALL increment counters by the specified cost value atomically

### Requirement 3: Time Window Calculations and Reset Time

**User Story:** As an API consumer, I want accurate reset time calculations and window boundary handling, so that I can predict when rate limits will be restored.

#### Acceptance Criteria

1. WHEN calculating window boundaries THEN the system SHALL align windows to epoch time boundaries (e.g., minute boundaries for 60s windows)
2. WHEN determining current window THEN the system SHALL use `currentTimeMillis / windowSizeMillis` for window identification
3. WHEN calculating resetTime THEN the system SHALL return the timestamp of the next window boundary
4. WHEN weighting previous window contribution THEN the system SHALL use formula: `previousWeight = (windowSize - timeIntoCurrentWindow) / windowSize`
5. IF time moves backwards THEN the system SHALL handle clock skew gracefully without corrupting counters
6. WHEN window size is not evenly divisible into standard time units THEN the system SHALL still maintain consistent window boundaries

### Requirement 4: Integration with Existing Architecture

**User Story:** As a service maintainer, I want seamless integration with the existing codebase, so that the new algorithm works consistently with established patterns and infrastructure.

#### Acceptance Criteria

1. WHEN implementing the algorithm THEN the system SHALL extend the existing `RateLimiterStrategy` interface
2. WHEN registering the strategy THEN the system SHALL follow the existing strategy registration pattern in the service layer
3. WHEN handling validation THEN the system SHALL reuse existing parameter validation for key, limit, window, and cost
4. IF algorithm selection occurs THEN the system SHALL route "sliding_window_counter" requests to the new strategy implementation
5. WHEN emitting metrics THEN the system SHALL follow existing metric naming conventions with algorithm-specific labels
6. WHEN logging operations THEN the system SHALL use consistent log levels and message formats with existing strategies

### Requirement 5: Lua Script Implementation Requirements

**User Story:** As a performance engineer, I want efficient Lua script execution, so that the sliding window counter maintains sub-millisecond Redis operation times.

#### Acceptance Criteria

1. WHEN executing the Lua script THEN the system SHALL complete all operations within 1ms average execution time
2. WHEN accessing Redis keys THEN the script SHALL minimize the number of Redis commands by batching operations
3. WHEN performing calculations THEN the script SHALL use integer arithmetic to avoid floating-point precision issues
4. IF script execution fails THEN the system SHALL log detailed error information including script name and input parameters
5. WHEN loading the script THEN the system SHALL validate Lua syntax during application startup
6. WHEN script returns results THEN the system SHALL return a structured response containing allowed status, remaining count, and reset time

### Requirement 6: Testing and Quality Assurance

**User Story:** As a quality engineer, I want comprehensive test coverage, so that the sliding window counter algorithm is reliable and maintainable.

#### Acceptance Criteria

1. WHEN running unit tests THEN the system SHALL execute tests without Docker dependencies using `./mvnw test`
2. WHEN running integration tests THEN the system SHALL execute Redis-dependent tests using `./mvnw verify` with `@Tag("integration")`
3. WHEN testing window transitions THEN the system SHALL verify correct counter promotion and reset behavior
4. IF Docker is unavailable THEN integration tests SHALL be skipped gracefully with appropriate logging
5. WHEN testing edge cases THEN the system SHALL cover scenarios including clock skew, Redis failures, and boundary conditions
6. WHEN measuring performance THEN the system SHALL validate that p99 latency remains under 10ms including Redis operations

### Requirement 7: Error Handling and Observability

**User Story:** As a system operator, I want comprehensive error handling and metrics, so that I can monitor and troubleshoot the sliding window counter algorithm effectively.

#### Acceptance Criteria

1. WHEN Redis is unavailable THEN the system SHALL fail open and allow all requests while logging the failure
2. WHEN Lua script execution times out THEN the system SHALL log timeout details and fail open
3. WHEN emitting metrics THEN the system SHALL record algorithm-specific metrics including window utilization and transition rates
4. IF invalid parameters are provided THEN the system SHALL return HTTP 400 with specific validation error messages
5. WHEN successful operations complete THEN the system SHALL record latency and success metrics with "sliding_window_counter" algorithm label
6. WHEN debugging is needed THEN the system SHALL provide detailed logging of window calculations and counter states

### Requirement 8: Performance and Memory Efficiency

**User Story:** As a performance engineer, I want memory-efficient rate limiting, so that the sliding window counter uses minimal Redis memory while maintaining accuracy.

#### Acceptance Criteria

1. WHEN storing counters THEN the system SHALL use only two Redis keys per rate limit key (current and previous windows)
2. WHEN calculating memory usage THEN the system SHALL require O(1) memory per rate limit key regardless of request volume
3. WHEN processing requests THEN the system SHALL maintain throughput of 10,000+ requests per second per instance
4. IF memory usage grows THEN expired window counters SHALL be automatically cleaned up by Redis TTL
5. WHEN comparing to sliding window log THEN the system SHALL demonstrate significantly lower memory usage for high-volume keys
6. WHEN accuracy is measured THEN the system SHALL provide approximation error within 5% of exact sliding window calculation

### Requirement 9: Documentation and Maintenance

**User Story:** As a developer, I want clear documentation and maintainable code, so that the sliding window counter algorithm can be understood and modified by the team.

#### Acceptance Criteria

1. WHEN reviewing the implementation THEN the system SHALL include comprehensive JavaDoc comments explaining the algorithm logic
2. WHEN examining the Lua script THEN the script SHALL contain inline comments explaining each calculation step
3. WHEN reading the code THEN variable names SHALL clearly indicate their purpose (e.g., currentWindowCount, previousWindowWeight)
4. IF algorithm behavior needs explanation THEN the system SHALL include examples in comments showing window transition scenarios
5. WHEN maintaining the code THEN the implementation SHALL follow existing code style and naming conventions
6. WHEN updating the algorithm THEN changes SHALL be backward compatible with existing Redis key schemas

### Requirement 10: Migration and Deployment Safety

**User Story:** As a deployment engineer, I want safe deployment of the new algorithm, so that existing functionality remains unaffected during rollout.

#### Acceptance Criteria

1. WHEN deploying the new algorithm THEN existing algorithm implementations SHALL remain unchanged and functional
2. WHEN clients request "sliding_window_counter" THEN the system SHALL route to the new implementation without affecting other algorithms
3. IF the new algorithm fails THEN other algorithms SHALL continue operating normally
4. WHEN rolling back THEN the system SHALL gracefully handle removal of sliding window counter support
5. WHEN testing deployment THEN all existing integration tests SHALL continue passing
6. WHEN monitoring deployment THEN metrics SHALL clearly distinguish between algorithm types for troubleshooting