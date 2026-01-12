# Design Decisions

## Overview

This document captures key architectural and implementation decisions made during the development of the distributed rate limiter service.

## ADR-001: Redis + Lua Scripts for Atomicity

### Context
Distributed rate limiting requires atomic operations across multiple Redis commands to prevent race conditions between concurrent requests from different service instances.

### Decision
Use Redis Lua scripts to ensure atomicity of multi-step operations.

### Rationale
- **Atomicity**: Lua scripts execute atomically in Redis
- **Performance**: Single network round-trip vs multiple commands
- **Consistency**: Eliminates race conditions between service instances
- **Simplicity**: Logic centralized in Redis, not distributed across instances

### Alternatives Considered
- **Redis Transactions (MULTI/EXEC)**: Not atomic with conditional logic
- **Distributed Locks**: Complex, performance overhead, failure modes
- **Application-level Coordination**: Race conditions, complexity

### Trade-offs
- **Pros**: Strong consistency, high performance, simple failure model
- **Cons**: Redis-specific, Lua learning curve, debugging complexity

---

## ADR-002: Two-Bucket Sliding Window Counter

### Context
Need memory-efficient sliding window rate limiting that balances accuracy with resource usage.

### Decision
Implement sliding window counter using only two buckets (current + previous window) with time-based weighting.

### Rationale
- **Memory Efficiency**: O(1) vs O(n) for exact sliding window log
- **Performance**: Constant-time operations regardless of request volume
- **Accuracy**: 90-95% accuracy sufficient for most API rate limiting
- **Simplicity**: Easy to understand and maintain

### Algorithm Details
```
estimatedCount = currentWindowCount + floor(previousWindowCount × weight)
weight = (windowSize - timeIntoCurrentWindow) / windowSize
```

### Alternatives Considered
- **Exact Sliding Window Log**: Perfect accuracy but O(n) memory
- **Fixed Window**: O(1) memory but poor accuracy at boundaries
- **Token Bucket**: Different use case (burst handling vs time windows)

### Trade-offs
- **Pros**: Memory efficient, high performance, good accuracy
- **Cons**: Approximation can allow ~25% extra requests in worst case

### Acceptable Error Bounds
- **Typical**: 5-10% error under normal request patterns
- **Worst Case**: Up to 25% error during window boundary transitions
- **Direction**: Conservative (tends to allow slightly more than deny)

---

## ADR-003: Fail-Open Strategy

### Context
When Redis becomes unavailable, the service must choose between failing closed (deny all requests) or failing open (allow all requests).

### Decision
Implement fail-open behavior: allow all requests when Redis is unavailable.

### Rationale
- **Availability**: Prevents rate limiter from becoming single point of failure
- **Graceful Degradation**: Service continues operating with reduced functionality
- **Recovery**: Automatic recovery when Redis becomes available
- **User Experience**: Better than complete service outage

### Implementation
```java
} catch (Exception e) {
    logger.error("Rate limit execution failed, failing open", e);
    return RateLimitResponse.allowed(request.getLimit() - 1, resetTime);
}
```

### Alternatives Considered
- **Fail-Closed**: Deny all requests when Redis unavailable
- **Circuit Breaker**: Complex state management, still needs fail-open/closed decision
- **Local Caching**: Inconsistent across instances, complex cache invalidation

### Trade-offs
- **Pros**: High availability, simple implementation, predictable behavior
- **Cons**: Temporary loss of rate limiting protection during outages

---

## ADR-004: TTL-Only Cleanup Strategy

### Context
Redis keys for rate limiting need cleanup to prevent memory leaks, especially for algorithms that create time-based keys.

### Decision
Use Redis TTL (Time To Live) for automatic key cleanup instead of active cleanup processes.

### Rationale
- **Simplicity**: No background cleanup jobs or complex lifecycle management
- **Reliability**: Redis handles cleanup automatically, no external dependencies
- **Performance**: No additional Redis operations for cleanup
- **Consistency**: TTL is atomic and consistent across Redis operations

### Implementation
- **Fixed Window**: TTL = 2 × window size
- **Sliding Window Counter**: TTL = 2 × window size  
- **Token Bucket**: TTL = configurable (default 24h)
- **Sliding Window Log**: TTL + periodic cleanup for efficiency

### Alternatives Considered
- **Background Cleanup Jobs**: Complex scheduling, failure modes
- **Lazy Cleanup**: Cleanup during reads, inconsistent performance
- **Manual Cleanup**: Operational overhead, risk of memory leaks

### Trade-offs
- **Pros**: Simple, reliable, automatic, no operational overhead
- **Cons**: Memory usage until TTL expires, less precise cleanup timing

---

## ADR-005: Strategy Pattern for Algorithms

### Context
Need to support multiple rate limiting algorithms with different characteristics and use cases.

### Decision
Implement Strategy pattern with `RateLimiterStrategy` interface and algorithm-specific implementations.

### Rationale
- **Extensibility**: Easy to add new algorithms without changing core service
- **Separation of Concerns**: Each algorithm encapsulated in its own class
- **Testability**: Algorithms can be tested independently
- **Runtime Selection**: Algorithm chosen per request based on client specification

### Interface Design
```java
public interface RateLimiterStrategy {
    RateLimitResponse execute(RateLimitRequest request);
    RateLimitAlgorithm getAlgorithm();
    void validateRequest(RateLimitRequest request);
}
```

### Alternatives Considered
- **Single Monolithic Class**: Hard to maintain, test, and extend
- **Separate Services**: Over-engineering, deployment complexity
- **Configuration-Based**: Less flexible, harder to customize behavior

### Trade-offs
- **Pros**: Clean separation, extensible, testable, maintainable
- **Cons**: Slight complexity overhead, more classes to manage

---

## ADR-006: Structured Logging for Observability

### Context
Production rate limiting service needs comprehensive logging for debugging, monitoring, and security analysis.

### Decision
Implement structured logging with consistent format across all rate limiting decisions.

### Format
```
Rate limit exceeded: key=api:user:123, algorithm=sliding_window_counter, 
limit=100, window=60000ms, remaining=0, current=45, previous=60, 
weight=0.750, retryAfter=15s
```

### Rationale
- **Searchability**: Structured logs are easily searchable and filterable
- **Monitoring**: Can be parsed by log aggregation systems
- **Security**: Consistent format helps detect abuse patterns
- **Debugging**: Rich context for troubleshooting issues

### Key Principles
- **No PII**: Keys are sanitized to prevent personal information exposure
- **Consistent Format**: Same structure across all algorithms
- **Rich Context**: Include all relevant decision factors
- **Performance**: Use appropriate log levels (DEBUG for allowed, INFO for denied)

### Alternatives Considered
- **Unstructured Logging**: Hard to parse and analyze
- **Metrics Only**: Less context for debugging
- **Separate Audit Log**: Additional complexity, potential inconsistency

### Trade-offs
- **Pros**: Excellent observability, security analysis, debugging support
- **Cons**: Slightly higher log volume, need for log management

---

## ADR-007: Property-Based Testing Strategy

### Context
Rate limiting algorithms have complex edge cases and mathematical properties that are difficult to test with traditional example-based tests.

### Decision
Implement property-based testing alongside traditional unit tests to verify algorithmic correctness.

### Properties Tested
- **Monotonicity**: Remaining count never increases without time passage
- **Boundary Behavior**: Correct handling of window transitions
- **Cost Handling**: Proper support for requests with cost > 1
- **Approximation Bounds**: Error stays within acceptable limits

### Implementation
```java
@Property
void remainingCountNeverIncreases(@ForAll RateLimitRequest request) {
    // Property implementation
}
```

### Rationale
- **Correctness**: Catches edge cases that example tests might miss
- **Confidence**: Mathematical properties provide strong correctness guarantees
- **Regression Prevention**: Properties continue to hold as code evolves
- **Documentation**: Properties serve as executable specifications

### Alternatives Considered
- **Example-Based Tests Only**: May miss edge cases
- **Formal Verification**: Too complex for this domain
- **Manual Testing**: Not scalable, not repeatable

### Trade-offs
- **Pros**: High confidence in correctness, catches subtle bugs
- **Cons**: Learning curve, longer test execution time

---

## Future Considerations

### Potential Improvements
1. **Redis Cluster Support**: For higher scale deployments
2. **Multi-Region Consistency**: Cross-region rate limiting
3. **Advanced Algorithms**: Adaptive rate limiting, ML-based prediction
4. **Performance Optimizations**: Connection pooling, pipelining

### Monitoring Evolution
- **Custom Metrics**: Algorithm-specific performance metrics
- **Distributed Tracing**: Request correlation across instances
- **Anomaly Detection**: Automated detection of abuse patterns

### Operational Enhancements
- **Configuration Management**: Dynamic configuration updates
- **A/B Testing**: Algorithm comparison in production
- **Capacity Planning**: Automated scaling recommendations