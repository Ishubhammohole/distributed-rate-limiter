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
| **Sliding Window Counter** | Memory-efficient approximation | O(1) | High | Good | ✅ Implemented |

### When to Use Each Algorithm

- **Token Bucket**: Default choice for public APIs. Allows bursts while maintaining average rate.
- **Sliding Window Log**: When you need perfect accuracy and can afford higher memory usage.
- **Fixed Window**: Simple quotas with acceptable edge-case bursts at window boundaries.
- **Sliding Window Counter**: Best balance of accuracy and memory efficiency.

## Failure Behavior

**Fail-Open Strategy**: When Redis is unavailable, the service allows all requests to maintain availability. This prevents the rate limiter from becoming a single point of failure.

**Circuit Breaker**: Automatic recovery with exponential backoff when Redis becomes available again.

**Graceful Degradation**: Under extreme load, prioritizes availability over perfect accuracy.

## Quick Start (3 Commands)

> **⚠️ CURRENT STATUS**: All 4 rate limiting algorithms implemented with Redis integration  
> Service provides production-ready rate limiting with atomic operations and fail-open behavior.

```bash
# 1. Start infrastructure (Redis, Prometheus, Grafana)
docker-compose -f infra/docker-compose.yml up -d

# 2. Build and start the service
cd ratelimiter-service && mvn spring-boot:run

# 3. Test the API with real rate limiting
curl -X POST http://localhost:8080/api/v1/ratelimit/check \
  -H "Content-Type: application/json" \
  -d '{"key":"test:user","algorithm":"sliding_window_counter","limit":10,"window":"60s","cost":1}'
```

## Testing

### Run All Tests
```bash
cd ratelimiter-service
mvn test
```

### Integration Tests (with Testcontainers)
```bash
mvn test -Dtest="*IntegrationTest"
```

### Property-Based Tests
```bash
mvn test -Dtest="*Properties"
```

### Test Coverage
```bash
mvn jacoco:report
open target/site/jacoco/index.html
```

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
**Quality**: >95% test coverage, property-based testing, comprehensive observability  
**Ops**: 3-command local setup, fail-open reliability, Grafana dashboards  

**Key Engineering Decisions**:
- Redis Lua scripts for atomic distributed operations
- Multiple algorithms optimized for different use cases  
- Fail-open strategy for high availability
- Comprehensive property-based testing for correctness
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