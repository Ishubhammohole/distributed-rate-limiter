# Architecture

## System Overview

The distributed rate limiter follows a stateless, horizontally scalable architecture where all rate limiting state is maintained in Redis. Each service instance operates independently while maintaining consistency through atomic Lua script execution.

## High-Level Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Client App    │    │   Client App    │    │   Client App    │
└─────────┬───────┘    └─────────┬───────┘    └─────────┬───────┘
          │                      │                      │
          └──────────────────────┼──────────────────────┘
                                 │
                    ┌─────────────▼─────────────┐
                    │      Load Balancer       │
                    └─────────────┬─────────────┘
                                 │
          ┌──────────────────────┼──────────────────────┐
          │                      │                      │
    ┌─────▼─────┐          ┌─────▼─────┐          ┌─────▼─────┐
    │Rate Limiter│          │Rate Limiter│          │Rate Limiter│
    │Instance 1  │          │Instance 2  │          │Instance N  │
    └─────┬─────┘          └─────┬─────┘          └─────┬─────┘
          │                      │                      │
          └──────────────────────┼──────────────────────┘
                                 │
                    ┌─────────────▼─────────────┐
                    │      Redis Cluster       │
                    │   (Shared State Store)   │
                    └──────────────────────────┘
```

## Component Architecture

### Rate Limiter Service Instance

```
┌─────────────────────────────────────────────────────────────┐
│                Rate Limiter Instance                        │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────┐  │
│  │  REST API       │  │  Metrics        │  │  Health     │  │
│  │  Controller     │  │  Collector      │  │  Monitor    │  │
│  └─────────┬───────┘  └─────────┬───────┘  └─────────────┘  │
│            │                    │                           │
│  ┌─────────▼─────────────────────▼─────────────────────────┐  │
│  │              Rate Limit Service                        │  │
│  └─────────┬───────────────────────────────────────────────┘  │
│            │                                               │
│  ┌─────────▼─────────┐  ┌─────────────────┐  ┌─────────────┐  │
│  │  Algorithm        │  │  Lua Script     │  │  Redis      │  │
│  │  Implementations  │  │  Manager        │  │  Client     │  │
│  └───────────────────┘  └─────────────────┘  └─────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Data Flow

### Request Processing Flow

1. **Request Ingress**: Client sends rate limit check request
2. **Validation**: Request parameters validated and sanitized
3. **Algorithm Selection**: Appropriate algorithm selected based on configuration
4. **Script Execution**: Lua script executed atomically in Redis
5. **Decision Processing**: Allow/deny decision processed and metrics recorded
6. **Response**: Structured response returned to client

### Detailed Flow Diagram

```
Client Request
      │
      ▼
┌─────────────┐
│ Validation  │ ──── Invalid ────► 400 Bad Request
└─────┬───────┘
      │ Valid
      ▼
┌─────────────┐
│ Algorithm   │
│ Selection   │
└─────┬───────┘
      │
      ▼
┌─────────────┐
│ Lua Script  │ ──── Redis Down ──► Fail-Open (Allow)
│ Execution   │
└─────┬───────┘
      │ Success
      ▼
┌─────────────┐
│ Metrics     │
│ Recording   │
└─────┬───────┘
      │
      ▼
┌─────────────┐
│ Response    │
│ Formation   │
└─────┬───────┘
      │
      ▼
Client Response
```

## Key Design Decisions

### 1. Stateless Service Design

**Decision**: All rate limiting state stored in Redis, service instances are stateless.

**Rationale**:
- Enables horizontal scaling without coordination
- Simplifies deployment and operations
- Allows for rolling updates without state loss
- Provides natural disaster recovery through Redis persistence

**Trade-offs**:
- Network latency to Redis on every request
- Dependency on Redis availability
- Slightly higher complexity vs. in-memory solutions

### 2. Lua Script Atomicity

**Decision**: All rate limiting operations implemented as atomic Lua scripts in Redis.

**Rationale**:
- Guarantees consistency across distributed instances
- Eliminates race conditions in concurrent scenarios
- Reduces network round-trips (single Redis operation)
- Leverages Redis's single-threaded execution model

**Trade-offs**:
- Lua script complexity vs. application logic
- Limited debugging capabilities for scripts
- Redis version compatibility requirements

### 3. Fail-Open Strategy

**Decision**: When Redis is unavailable, allow all requests (fail-open).

**Rationale**:
- Prevents rate limiter from becoming single point of failure
- Maintains service availability during Redis outages
- Aligns with "availability over consistency" for this use case

**Trade-offs**:
- Temporary loss of rate limiting protection
- Potential for abuse during outages
- Requires monitoring to detect fail-open scenarios

### 4. Algorithm Pluggability

**Decision**: Clean interface for multiple rate limiting algorithms.

**Rationale**:
- Different use cases require different algorithms
- Allows for algorithm-specific optimizations
- Enables easy addition of new algorithms
- Supports A/B testing of algorithm performance

**Trade-offs**:
- Additional complexity vs. single algorithm
- Need for algorithm-specific configuration validation
- Increased testing surface area

## Component Details

### REST API Controller

**Responsibilities**:
- HTTP request/response handling
- Input validation and sanitization
- Error handling and status code mapping
- Request/response serialization

**Key Classes**:
- `RateLimitController`: Main REST endpoint
- `RateLimitRequest`: Request DTO with validation
- `RateLimitResponse`: Response DTO
- `GlobalExceptionHandler`: Centralized error handling

### Rate Limit Service

**Responsibilities**:
- Core business logic orchestration
- Algorithm selection and configuration
- Metrics collection coordination
- Circuit breaker implementation

**Key Classes**:
- `RateLimitService`: Main service interface
- `RateLimitServiceImpl`: Service implementation
- `CircuitBreakerService`: Redis failure handling

### Algorithm Implementations

**Responsibilities**:
- Algorithm-specific logic implementation
- Lua script generation and management
- Configuration validation
- Algorithm-specific metrics

**Key Classes**:
- `RateLimitAlgorithm`: Algorithm interface
- `TokenBucketAlgorithm`: Token bucket implementation
- `SlidingWindowLogAlgorithm`: Sliding window log implementation
- `FixedWindowCounterAlgorithm`: Fixed window implementation
- `SlidingWindowCounterAlgorithm`: Sliding window counter implementation

### Lua Script Manager

**Responsibilities**:
- Lua script loading and caching
- Script execution coordination
- Error handling for script failures
- Script versioning and updates

**Key Classes**:
- `LuaScriptManager`: Script management interface
- `RedisLuaScriptManager`: Redis-specific implementation
- `ScriptCache`: In-memory script caching

### Redis Client

**Responsibilities**:
- Redis connection management
- Connection pooling optimization
- Health monitoring
- Failover handling

**Key Classes**:
- `RedisConfiguration`: Connection configuration
- `RedisHealthIndicator`: Health check implementation
- `RedisMetricsCollector`: Connection metrics

## Scalability Considerations

### Horizontal Scaling

- **Stateless Design**: Instances can be added/removed without coordination
- **Load Distribution**: Any instance can handle any request
- **Redis Sharding**: Redis cluster support for data distribution
- **Connection Pooling**: Optimized connection usage per instance

### Performance Optimization

- **Script Caching**: Lua scripts cached in Redis and application memory
- **Connection Pooling**: Lettuce connection pooling for high throughput
- **Batch Operations**: Where possible, batch multiple operations
- **Hot Key Handling**: Strategies for handling high-traffic keys

### Monitoring and Observability

- **Comprehensive Metrics**: Request rates, latency, error rates, Redis health
- **Distributed Tracing**: Request correlation across instances
- **Health Checks**: Deep health checks including Redis connectivity
- **Alerting**: Proactive alerting on performance degradation

## Security Considerations

- **Input Validation**: Comprehensive validation of all request parameters
- **Key Sanitization**: Proper sanitization of rate limit keys
- **DoS Protection**: Built-in protection against various DoS scenarios
- **Resource Limits**: Configurable limits on key lengths, request sizes
- **Audit Logging**: Comprehensive logging for security analysis

See [security-abuse.md](security-abuse.md) for detailed security considerations.