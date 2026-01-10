# Consistency Model

## Overview

The distributed rate limiter provides strong consistency guarantees through Redis-backed atomic operations. This document defines the consistency model, invariants, and timing guarantees of the system.

## Consistency Guarantees

### Strong Consistency

The system provides **strong consistency** for rate limiting decisions across all instances through:

1. **Atomic Operations**: All rate limit state changes are atomic via Lua scripts
2. **Single Source of Truth**: Redis serves as the authoritative state store
3. **Serializable Execution**: Redis's single-threaded model ensures serializable execution

### Consistency Properties

| Property | Guarantee | Implementation |
|----------|-----------|----------------|
| **Atomicity** | Rate limit checks and updates are atomic | Lua scripts in Redis |
| **Consistency** | All instances see the same state | Shared Redis state store |
| **Isolation** | Concurrent requests don't interfere | Redis single-threaded execution |
| **Durability** | State survives Redis restarts | Redis persistence (RDB/AOF) |

## System Invariants

### Core Invariants

These invariants must hold at all times during normal operation:

#### 1. Rate Limit Accuracy
```
For any time window W and limit L:
  actual_requests_allowed ≤ L + burst_allowance
```

#### 2. Token Bucket Conservation
```
For token bucket algorithm:
  0 ≤ current_tokens ≤ bucket_capacity
  token_refill_rate = configured_rate
```

#### 3. Sliding Window Precision
```
For sliding window algorithms:
  request_count = |{requests where timestamp > (now - window_size)}|
```

#### 4. State Consistency
```
For any key K across instances I1, I2, ..., In:
  state(K, I1) = state(K, I2) = ... = state(K, In)
```

#### 5. Monotonic Time Progression
```
For any sequence of operations on key K:
  timestamp(op_i) ≤ timestamp(op_i+1)
```

### Algorithm-Specific Invariants

#### Token Bucket Algorithm
- **Token Conservation**: Tokens are never created or destroyed, only consumed or refilled
- **Refill Rate**: Tokens are refilled at exactly the configured rate
- **Capacity Limit**: Token count never exceeds bucket capacity
- **Non-negative Tokens**: Token count is never negative

#### Sliding Window Log Algorithm
- **Request Ordering**: Requests are stored in chronological order
- **Window Boundary**: Only requests within the time window are counted
- **Exact Counting**: Request count is exactly the number of requests in the window
- **Memory Cleanup**: Expired requests are removed from the log

#### Fixed Window Counter Algorithm
- **Window Alignment**: Windows are aligned to fixed time boundaries
- **Counter Reset**: Counters reset to zero at window boundaries
- **Atomic Increment**: Counter increments are atomic

#### Sliding Window Counter Algorithm
- **Window Approximation**: Provides approximation of sliding window behavior
- **Memory Efficiency**: Uses constant memory regardless of request rate
- **Smooth Transitions**: Provides smoother rate limiting than fixed windows

## Time Source and Synchronization

### Time Source

**Primary Time Source**: Redis server time via `TIME` command
- Ensures consistent time across all rate limiter instances
- Eliminates clock skew between application servers
- Provides microsecond precision for accurate rate limiting

**Fallback Time Source**: System time when Redis is unavailable
- Used only during fail-open scenarios
- May introduce slight inconsistencies during Redis outages
- Automatically switches back to Redis time when available

### Time Synchronization Strategy

```lua
-- Redis Lua script time acquisition
local current_time = redis.call('TIME')
local timestamp = current_time[1] + (current_time[2] / 1000000)
```

**Benefits**:
- Eliminates clock drift between instances
- Provides consistent time reference for all operations
- Ensures accurate window calculations across distributed instances

**Trade-offs**:
- Additional Redis call overhead (mitigated by script execution)
- Dependency on Redis for time synchronization
- Potential time inconsistency during Redis failover

## Atomicity Model

### Lua Script Atomicity

All rate limiting operations are implemented as atomic Lua scripts:

```lua
-- Example: Token bucket atomic operation
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local requested_tokens = tonumber(ARGV[3])

-- Get current state atomically
local current_state = redis.call('HMGET', key, 'tokens', 'last_refill')
local current_tokens = tonumber(current_state[1]) or capacity
local last_refill = tonumber(current_state[2]) or current_time

-- Calculate refill and update state atomically
local time_elapsed = current_time - last_refill
local tokens_to_add = math.floor(time_elapsed * refill_rate)
local new_tokens = math.min(capacity, current_tokens + tokens_to_add)

-- Make decision and update state atomically
if new_tokens >= requested_tokens then
    new_tokens = new_tokens - requested_tokens
    redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', current_time)
    return {1, new_tokens}  -- Allow, remaining tokens
else
    redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', current_time)
    return {0, new_tokens}  -- Deny, remaining tokens
end
```

### Atomicity Guarantees

1. **Read-Modify-Write Atomicity**: All state reads, modifications, and writes occur atomically
2. **Decision Consistency**: Rate limit decisions are always based on current state
3. **State Update Atomicity**: State updates are never partially applied
4. **Multi-Key Atomicity**: Operations on multiple keys (if needed) are atomic

## Consistency During Failures

### Redis Availability

#### Normal Operation
- **Strong Consistency**: All instances see identical state
- **Immediate Consistency**: State changes are immediately visible to all instances
- **Atomic Updates**: All updates are atomic and consistent

#### Redis Unavailable (Fail-Open)
- **Eventual Consistency**: Instances may make independent decisions
- **Conservative Approach**: Prefer allowing requests over blocking
- **State Recovery**: Automatic state synchronization when Redis returns

#### Redis Partitioned
- **Partition Tolerance**: Instances connected to Redis maintain consistency
- **Isolated Instances**: Disconnected instances fail-open independently
- **Automatic Recovery**: Instances rejoin consistent state when partition heals

### Consistency Recovery

When Redis becomes available after an outage:

1. **Immediate Synchronization**: All instances immediately use Redis state
2. **No State Merge**: Previous fail-open decisions are not retroactively applied
3. **Clean Slate**: Rate limiting resumes with current Redis state
4. **Metric Reconciliation**: Metrics may show temporary inconsistencies

## Performance vs. Consistency Trade-offs

### Consistency Levels

The system can be configured for different consistency vs. performance trade-offs:

#### Strict Consistency (Default)
- Every operation queries Redis for current time and state
- Highest consistency, moderate performance impact
- Recommended for most use cases

#### Relaxed Consistency (Future Enhancement)
- Cache Redis time for short periods (e.g., 100ms)
- Slight consistency relaxation, improved performance
- Suitable for high-throughput scenarios with acceptable drift

### Performance Optimizations

While maintaining consistency:

1. **Script Caching**: Lua scripts are cached in Redis to avoid recompilation
2. **Connection Pooling**: Efficient connection reuse reduces overhead
3. **Batch Operations**: Where possible, batch multiple operations
4. **Pipelining**: Use Redis pipelining for non-atomic operations

## Monitoring Consistency

### Consistency Metrics

Key metrics to monitor consistency:

- **State Divergence**: Measure state differences across instances (should be zero)
- **Time Skew**: Monitor time differences between instances and Redis
- **Atomic Operation Success Rate**: Track Lua script execution success
- **Fail-Open Events**: Count and duration of fail-open scenarios

### Consistency Alerts

Critical alerts for consistency violations:

- **Redis Connectivity Loss**: Alert when instances lose Redis connection
- **Script Execution Failures**: Alert on Lua script execution errors
- **Time Synchronization Issues**: Alert on significant time skew
- **State Inconsistency**: Alert if state divergence is detected

## Testing Consistency

### Property-Based Testing

Consistency properties are validated through property-based tests:

```java
@Property
void atomicOperationConsistency(@ForAll String key, @ForAll int limit) {
    // Test that concurrent operations maintain consistency
    // Verify that total allowed requests never exceed limit
    // Ensure state remains consistent across all operations
}
```

### Consistency Test Scenarios

1. **Concurrent Access**: Multiple instances accessing same keys simultaneously
2. **Redis Failover**: Consistency during Redis master/slave failover
3. **Network Partitions**: Behavior during network splits
4. **Time Skew**: Consistency with clock differences
5. **High Load**: Consistency under extreme load conditions

## Consistency Guarantees Summary

| Scenario | Consistency Level | Behavior |
|----------|------------------|----------|
| **Normal Operation** | Strong | All instances see identical state immediately |
| **Redis Unavailable** | Eventual | Instances fail-open, sync when Redis returns |
| **Network Partition** | Partition Tolerant | Connected instances maintain consistency |
| **High Load** | Strong | Consistency maintained under load |
| **Redis Failover** | Strong | Brief inconsistency during failover, then strong |

The system prioritizes **availability over consistency** during failures while maintaining **strong consistency** during normal operation, following the principles of the CAP theorem for distributed systems.