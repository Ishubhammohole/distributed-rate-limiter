# Distributed Rate Limiter

A production-grade, horizontally scalable rate limiting service built with Java 17 and Spring Boot 3.x. Provides atomic rate limiting operations using Redis and Lua scripts with comprehensive observability and multiple algorithm support.

## Problem Statement

Modern distributed systems require robust rate limiting to:
- Protect APIs from abuse and DoS attacks
- Ensure fair resource allocation across clients
- Maintain system stability under load
- Provide predictable performance guarantees

This service solves these challenges with a stateless, horizontally scalable architecture that maintains consistency across instances through Redis-backed atomic operations.

## System Guarantees

✅ **Performance**: <10ms p99 latency, 10,000+ RPS per instance  
✅ **Consistency**: Atomic operations across distributed instances  
✅ **Availability**: Fail-open behavior when Redis is unavailable  
✅ **Accuracy**: Precise rate limiting with configurable algorithms  
✅ **Observability**: Comprehensive metrics and dashboards  

## Non-Goals

❌ Authentication/authorization (bring your own)  
❌ Request routing or load balancing  
❌ Long-term analytics storage  
❌ Multi-tenancy isolation (single Redis namespace)  

## API Contract

### Rate Limit Check

**Endpoint**: `POST /api/v1/ratelimit/check`

**Request**:
```json
{
  "key": "user:12345",
  "algorithm": "token_bucket",
  "limit": 100,
  "window": "60s",
  "cost": 1
}
```

**Response (Allowed)**:
```json
{
  "allowed": true,
  "remaining": 95,
  "resetTime": "2025-01-09T15:30:00Z",
  "retryAfter": null
}
```

**Response (Rate Limited)**:
```json
{
  "allowed": false,
  "remaining": 0,
  "resetTime": "2025-01-09T15:30:00Z",
  "retryAfter": "45s"
}
```

## Supported Algorithms

| Algorithm | Use Case | Memory | Accuracy | Burst Handling | Status |
|-----------|----------|---------|----------|----------------|---------|
| **Token Bucket** | API rate limiting | O(1) | High | Excellent | ✅ Implemented |
| **Sliding Window Log** | Precise tracking | O(n) | Perfect | Good | ✅ Implemented |
| **Fixed Window** | Simple quotas | O(1) | Good | Poor | ✅ Implemented |
| **Sliding Window Counter** | Memory-efficient approximation | O(1) | High (~75-90%) | Good | ✅ Implemented |

### When to Use Each Algorithm

- **Token Bucket**: Default choice for public APIs. Allows bursts while maintaining average rate.
- **Sliding Window Log**: When you need perfect accuracy and can afford higher memory usage.
- **Fixed Window**: Simple quotas with acceptable edge-case bursts at window boundaries.
- **Sliding Window Counter**: Memory-efficient approximation with ~75-90% accuracy. Best for high-scale systems.

### Sliding Window Counter Algorithm

The sliding window counter provides **memory-efficient approximate rate limiting** using only two counters per key. This is an approximation algorithm that trades perfect accuracy for O(1) memory usage.

```
Time:     0s    5s    10s   15s   20s
Windows:  [----W1----][----W2----][----W3----]
                      ^
                   Current time (12s into W2)

Previous Window (W1): 3 requests
Current Window (W2):  1 request

Weight calculation:
- Time into current window: 2s
- Weight = (10s - 2s) / 10s = 0.8
- Weighted previous: floor(3 × 0.8) = floor(2.4) = 2

Estimated total: 2 + 1 = 3 requests in sliding window
```

**Key Characteristics**:
- **Approximation**: Uses `floor()` function instead of exact timestamp tracking
- **Memory**: O(1) per key (maximum 2 Redis counters)
- **Performance**: Sub-millisecond Redis operations
- **Accuracy**: ~75-90% typical, ~50% worst case at window boundaries

### Approximation Behavior & Tradeoffs

**Why Approximation**:
- Exact sliding window requires O(n) memory to store all request timestamps
- This algorithm achieves O(1) memory by using weighted counters
- Suitable for high-scale systems where memory efficiency is critical

**Error Characteristics**:
- **Conservative bias**: Tends to allow slightly more requests than exact algorithm
- **Boundary effects**: Largest errors occur during window transitions
- **Predictable**: Error patterns are well-understood and documented

**Worst-Case Example**:
```
Window: 10s, Limit: 3
Timeline:
- 9.6s: Request 1 ✅ (allowed)
- 9.7s: Request 2 ✅ (allowed) 
- 9.8s: Request 3 ✅ (allowed)
- 10.1s: Request 4 ✅ (allowed by approximation)

Exact sliding window at 10.1s:
- Window: (0.1s, 10.1s]
- Contains: 4 requests → Should DENY

Sliding window counter at 10.1s:
- Previous window weight: (10s - 0.1s) / 10s = 0.99
- Estimated: floor(3 × 0.99) + 1 = 2 + 1 = 3 → ALLOWS

Result: 25% error (allows 4 requests instead of 3)
```

**When to Use**:
- ✅ High-scale APIs where memory efficiency is critical
- ✅ Systems that can tolerate occasional burst allowance
- ✅ Rate limiting where conservative errors are acceptable
- ❌ Applications requiring perfect accuracy
- ❌ Systems where any overage is unacceptable

## Failure Behavior

**Fail-Open Strategy**: When Redis is unavailable, the service allows all requests to maintain availability. This prevents the rate limiter from becoming a single point of failure.

**Circuit Breaker**: Automatic recovery with exponential backoff when Redis becomes available again.

**Graceful Degradation**: Under extreme load, prioritizes availability over perfect accuracy.

## Quick Start

```bash
# 1. Start all services
docker-compose up -d

# 2. Test the API
curl -X POST http://localhost:8080/api/v1/ratelimit/check \
  -H "Content-Type: application/json" \
  -d '{"key":"test:user","algorithm":"sliding_window_counter","limit":10,"window":"60s","cost":1}'

# 3. View dashboards at http://localhost:3000 (admin/admin)
```

## Testing

### Run All Tests
```bash
cd ratelimiter-service
./mvnw test
```

### Integration Tests (with Testcontainers)
```bash
./mvnw test -Dtest="*IntegrationTest"
```

### Reproduction Tests (Manual)
```bash
# Run specific reproduction test
./mvnw test -Dtest=SlidingWindowBoundaryReproTest

# Run all reproduction tests
./mvnw test -Dtest="com.ratelimiter.repro.*"
```

### Test Coverage
```bash
./mvnw jacoco:report
open target/site/jacoco/index.html
```

## Verification Scripts

The service includes verification scripts to validate correctness:

```bash
# Run all verification tests
make verify

# Individual verifications
./scripts/verify-semantic-truth.sh      # Validates approximation behavior
./scripts/verify-window-boundary.sh     # Tests window transitions
./scripts/verify-boundary-math.sh       # Verifies mathematical correctness
```

These scripts test the service end-to-end against Redis to ensure the algorithms work correctly according to their documented behavior and accuracy characteristics.

## Benchmarking

### Load Testing with K6
```bash
# Throughput test (10,000+ RPS target)
k6 run loadtest/k6/throughput-test.js

# Latency test (<10ms p99 target)
k6 run loadtest/k6/latency-test.js

# Multi-instance test
k6 run loadtest/k6/distributed-test.js
```

### Performance Validation
```bash
./scripts/benchmark.sh
```

Results are documented in [docs/benchmarks.md](docs/benchmarks.md).

## Observability

### Grafana Dashboards
- **URL**: http://localhost:3000 (admin/admin)
- **Dashboard**: "Rate Limiter Metrics" (auto-imported)

**Key Panels**:
- Request rates and success/failure ratios
- Latency percentiles (p50, p95, p99)
- Redis connection health and performance
- Algorithm-specific metrics (bucket utilization, etc.)
- Error rates and failure modes

### Prometheus Metrics
- **URL**: http://localhost:9090
- **Service Metrics**: http://localhost:8080/actuator/prometheus

**Key Metrics**:
- `rate_limiter_requests_total` - Total requests by algorithm and result
- `rate_limiter_request_duration` - Request latency histogram
- `rate_limiter_redis_operations` - Redis operation metrics
- `rate_limiter_bucket_utilization` - Algorithm-specific utilization

### Health Checks
- **Service Health**: http://localhost:8080/actuator/health
- **Redis Health**: Included in health endpoint

## Recruiter TL;DR

**What**: Production-grade distributed rate limiter service  
**Scale**: 10,000+ RPS, <10ms p99 latency, horizontally scalable  
**Tech**: Java 17, Spring Boot 3.x, Redis, Lua scripts, Docker  
**Quality**: >95% test coverage, property-based testing, observability  
**Ops**: 3-command local setup, fail-open reliability, Grafana dashboards  

**Key Engineering Decisions**:
- Redis Lua scripts for atomic distributed operations
- Multiple algorithms optimized for different use cases  
- Fail-open strategy for high availability
- Property-based testing for correctness
- Production-ready observability and operational tooling

## Architecture

See [docs/architecture.md](docs/architecture.md) for detailed system design, component interactions, and data flow.

## Documentation

- [Architecture](docs/architecture.md) - System design and components
- [Consistency Model](docs/consistency.md) - Distributed consistency guarantees  
- [Failure Modes](docs/failure-modes.md) - Failure handling and recovery
- [Security & Abuse Prevention](docs/security-abuse.md) - DoS protection and key security
- [Benchmarks](docs/benchmarks.md) - Performance methodology and results

## License

MIT License - see LICENSE file for details.