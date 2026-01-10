# Failure Modes and Recovery

## Overview

This document describes how the distributed rate limiter handles various failure scenarios, including Redis outages, network partitions, high latency, and hot-key scenarios. The system is designed with a fail-open philosophy to maintain availability.

## Redis Failure Scenarios

### Complete Redis Outage

**Scenario**: Redis cluster is completely unavailable.

**Detection**:
- Connection timeouts on Redis operations
- Health check failures
- Circuit breaker activation

**Behavior**:
```java
// Fail-open implementation
public RateLimitResult checkLimit(RateLimitRequest request) {
    try {
        return executeRedisOperation(request);
    } catch (RedisConnectionException e) {
        logger.warn("Redis unavailable, failing open for key: {}", request.getKey());
        metrics.incrementFailOpenCounter();
        return RateLimitResult.allow(request);
    }
}
```

**Recovery**:
1. **Automatic Retry**: Exponential backoff retry mechanism
2. **Circuit Breaker**: Prevents overwhelming Redis during recovery
3. **Health Monitoring**: Continuous health checks to detect recovery
4. **Gradual Resumption**: Slowly ramp up traffic to recovered Redis

**Metrics**:
- `rate_limiter_fail_open_total`: Count of fail-open events
- `rate_limiter_redis_connection_failures`: Redis connection failure count
- `rate_limiter_circuit_breaker_state`: Circuit breaker state (open/closed/half-open)

### Redis High Latency

**Scenario**: Redis is available but responding slowly (>100ms).

**Detection**:
- Request timeout monitoring
- Latency percentile alerts (p95 > threshold)
- Slow query logging

**Behavior**:
- **Timeout Configuration**: Aggressive timeouts (1-2 seconds) to fail fast
- **Circuit Breaker**: Opens on sustained high latency
- **Degraded Mode**: May switch to fail-open if latency exceeds thresholds

**Mitigation**:
```yaml
# Configuration for latency handling
rate-limiter:
  redis:
    command-timeout: 1000ms
    connection-timeout: 2000ms
    circuit-breaker:
      failure-threshold: 5
      timeout: 30s
```

**Recovery**:
- Monitor latency improvements
- Gradual circuit breaker closure
- Load shedding if necessary

### Redis Memory Pressure

**Scenario**: Redis running out of memory, causing evictions or failures.

**Detection**:
- Redis memory usage metrics
- Key eviction events
- Out-of-memory errors

**Behavior**:
- **Key TTL Strategy**: Aggressive TTLs on rate limit keys to free memory
- **Memory Monitoring**: Proactive monitoring of Redis memory usage
- **Graceful Degradation**: Reduce precision of algorithms if needed

**Prevention**:
```lua
-- Lua script with memory-conscious TTL
local ttl = math.max(window_size * 2, 3600) -- At least 1 hour TTL
redis.call('EXPIRE', key, ttl)
```

### Redis Cluster Failover

**Scenario**: Redis master fails, slave promotion in progress.

**Detection**:
- Connection errors to master
- Cluster topology changes
- Lettuce client failover events

**Behavior**:
- **Automatic Failover**: Lettuce client handles master/slave failover
- **Brief Inconsistency**: Short period of potential inconsistency during failover
- **Connection Retry**: Automatic reconnection to new master

**Recovery**:
- Lettuce client automatically discovers new topology
- Rate limiting resumes with new master
- Metrics track failover events and duration

## Network Partition Scenarios

### Rate Limiter to Redis Partition

**Scenario**: Network partition between rate limiter instances and Redis.

**Detection**:
- Connection timeouts
- Network connectivity checks
- Redis ping failures

**Behavior**:
- **Immediate Fail-Open**: Switch to fail-open mode immediately
- **Local Caching**: Brief local caching of decisions (if configured)
- **Partition Detection**: Distinguish between Redis failure and network partition

**Recovery**:
- Automatic reconnection when partition heals
- State synchronization with Redis
- Resume normal operation

### Partial Network Partition

**Scenario**: Some instances can reach Redis, others cannot.

**Detection**:
- Inconsistent health check results across instances
- Metrics divergence between instances
- Load balancer health check failures

**Behavior**:
- **Connected Instances**: Continue normal operation with Redis
- **Partitioned Instances**: Fail-open independently
- **No Coordination**: Instances don't coordinate during partition

**Recovery**:
- Partitioned instances reconnect when network heals
- Immediate synchronization with Redis state
- Metrics reconciliation

## Hot-Key Scenarios

### Single Hot Key

**Scenario**: One rate limit key receives extremely high traffic.

**Detection**:
- Key-level request rate monitoring
- Redis CPU usage spikes
- Increased latency for specific keys

**Mitigation Strategies**:

#### 1. Key Sharding
```java
// Distribute hot keys across multiple Redis keys
public String getShardedKey(String originalKey, int shardCount) {
    int shard = Math.abs(originalKey.hashCode()) % shardCount;
    return originalKey + ":shard:" + shard;
}
```

#### 2. Local Caching
```java
// Brief local caching for hot keys
@Cacheable(value = "rateLimitCache", key = "#request.key", 
           condition = "#request.isHotKey()")
public RateLimitResult checkHotKey(RateLimitRequest request) {
    return checkLimit(request);
}
```

#### 3. Load Shedding
```java
// Probabilistic load shedding for hot keys
if (isHotKey(request.getKey()) && shouldShed(request)) {
    return RateLimitResult.deny(request, "Load shedding active");
}
```

### Multiple Hot Keys

**Scenario**: Multiple keys become hot simultaneously.

**Detection**:
- Overall Redis CPU usage
- Increased global latency
- Throughput degradation

**Mitigation**:
- **Global Load Shedding**: Reduce overall traffic to Redis
- **Priority Queuing**: Prioritize certain key types
- **Circuit Breaker**: Global circuit breaker activation

## Application-Level Failures

### Rate Limiter Instance Failure

**Scenario**: Individual rate limiter instance crashes or becomes unresponsive.

**Detection**:
- Load balancer health checks
- Instance heartbeat monitoring
- Request timeout from clients

**Behavior**:
- **Load Balancer**: Routes traffic to healthy instances
- **No State Loss**: All state is in Redis, no local state lost
- **Immediate Recovery**: New instances can immediately serve traffic

**Recovery**:
- Restart failed instance
- Automatic load balancer re-inclusion
- No manual intervention required

### Memory Leaks or Resource Exhaustion

**Scenario**: Rate limiter instance experiencing memory leaks or resource exhaustion.

**Detection**:
- JVM memory usage monitoring
- Garbage collection metrics
- Response time degradation

**Behavior**:
- **Graceful Shutdown**: Attempt graceful shutdown when resources low
- **Circuit Breaker**: Activate circuit breaker to protect instance
- **Load Shedding**: Reduce load on affected instance

**Recovery**:
- Automatic instance restart
- Memory dump collection for analysis
- Resource limit adjustments if needed

## Cascading Failure Prevention

### Circuit Breaker Pattern

```java
@Component
public class RedisCircuitBreaker {
    private final CircuitBreaker circuitBreaker;
    
    public <T> T execute(Supplier<T> operation, Supplier<T> fallback) {
        return circuitBreaker.executeSupplier(
            Decorators.ofSupplier(operation)
                .withCircuitBreaker(circuitBreaker)
                .withFallback(fallback)
                .decorate()
        );
    }
}
```

### Bulkhead Pattern

- **Connection Pools**: Separate connection pools for different operations
- **Thread Pools**: Isolated thread pools for Redis operations
- **Resource Isolation**: Prevent one failure from affecting other operations

### Timeout Strategy

```yaml
# Aggressive timeouts to fail fast
rate-limiter:
  redis:
    command-timeout: 1000ms
    connection-timeout: 2000ms
  circuit-breaker:
    failure-threshold: 5
    slow-call-threshold: 800ms
    timeout: 30s
```

## Monitoring and Alerting

### Critical Alerts

1. **Redis Connectivity Loss**
   - Trigger: Redis connection failures > 5 in 1 minute
   - Action: Immediate investigation, check Redis cluster health

2. **High Fail-Open Rate**
   - Trigger: Fail-open rate > 10% for 5 minutes
   - Action: Check Redis performance and connectivity

3. **Circuit Breaker Open**
   - Trigger: Circuit breaker opens
   - Action: Investigate underlying Redis issues

4. **Hot Key Detection**
   - Trigger: Single key > 1000 RPS
   - Action: Consider key sharding or load shedding

### Monitoring Metrics

```yaml
# Key metrics for failure detection
metrics:
  - rate_limiter_redis_connection_failures_total
  - rate_limiter_fail_open_total
  - rate_limiter_circuit_breaker_state
  - rate_limiter_request_duration_seconds
  - rate_limiter_hot_key_events_total
  - redis_memory_usage_bytes
  - redis_connected_clients
```

## Recovery Procedures

### Redis Cluster Recovery

1. **Assess Damage**: Check Redis cluster status and data integrity
2. **Restore from Backup**: If necessary, restore from latest backup
3. **Gradual Traffic Resumption**: Slowly increase traffic to recovered cluster
4. **Monitor Performance**: Watch for performance issues during recovery

### Network Partition Recovery

1. **Verify Connectivity**: Ensure all instances can reach Redis
2. **Check State Consistency**: Verify Redis state is consistent
3. **Resume Normal Operation**: Allow instances to resume normal operation
4. **Reconcile Metrics**: Account for any metric discrepancies during partition

### Hot Key Mitigation

1. **Identify Hot Keys**: Use monitoring to identify problematic keys
2. **Implement Sharding**: Deploy key sharding for hot keys
3. **Adjust Rate Limits**: Consider adjusting limits for hot keys
4. **Monitor Effectiveness**: Verify mitigation effectiveness

## Testing Failure Scenarios

### Chaos Engineering

Regular chaos engineering exercises to test failure handling:

1. **Redis Outage Simulation**: Randomly kill Redis instances
2. **Network Partition Simulation**: Use network partitioning tools
3. **Load Testing**: Generate hot key scenarios
4. **Resource Exhaustion**: Simulate memory/CPU exhaustion

### Automated Testing

```java
@Test
void testRedisFailureHandling() {
    // Start with Redis available
    assertThat(rateLimiter.checkLimit(request)).isAllowed();
    
    // Simulate Redis outage
    redisContainer.stop();
    
    // Verify fail-open behavior
    assertThat(rateLimiter.checkLimit(request)).isAllowed();
    
    // Restore Redis
    redisContainer.start();
    
    // Verify normal operation resumes
    assertThat(rateLimiter.checkLimit(request)).hasConsistentState();
}
```

## Failure Recovery SLAs

| Failure Type | Detection Time | Recovery Time | Availability Impact |
|--------------|----------------|---------------|-------------------|
| Redis Outage | < 5 seconds | Immediate fail-open | None (fail-open) |
| Network Partition | < 10 seconds | Immediate fail-open | None (fail-open) |
| Hot Key | < 30 seconds | < 2 minutes | Minimal (load shedding) |
| Instance Failure | < 30 seconds | < 1 minute | None (load balancer) |
| Redis Recovery | N/A | < 30 seconds | Brief (reconnection) |

The system is designed to maintain **99.9% availability** even during infrastructure failures through its fail-open design and comprehensive failure handling mechanisms.