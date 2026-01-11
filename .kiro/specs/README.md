# Project Specifications Index

## Active Specifications

### Sliding Window Counter Algorithm Implementation
**Location:** `.kiro/specs/sliding-window-counter/`

- **requirements.md** - EARS-format requirements for implementing the missing Sliding Window Counter rate limiting algorithm
- **design.md** - Architecture design, Redis key schema, Lua script contracts, and component interfaces  
- **impl.md** - Step-by-step implementation plan with testing strategy and Maven guidance

**Status:** Ready for implementation  
**Target:** Complete the rate limiter service by adding the final algorithm

## Implementation Status

**✅ COMPLETED ALGORITHMS:**
- Token Bucket (`TokenBucketRateLimiterStrategy.java`)
- Sliding Window Log (`SlidingWindowLogRateLimiterStrategy.java`)
- Fixed Window (`FixedWindowRateLimiterStrategy.java`)

**🎯 NEXT IMPLEMENTATION:**
- Sliding Window Counter (specification complete)

**✅ INFRASTRUCTURE COMPLETE:**
- Maven test separation: `./mvnw test` (unit), `./mvnw verify` (integration)
- Redis + Lua script framework
- Strategy pattern architecture
- Metrics and observability
- API contract and validation

## Maven Test Execution

**Unit Tests (No Docker Required):**
```bash
./mvnw test
```

**Integration Tests (Docker Required, Graceful Skip):**
```bash
./mvnw verify
```

Integration tests use `@Tag("integration")` and `@Testcontainers(disabledWithoutDocker = true)` to skip gracefully when Docker is unavailable.

## Usage

To implement the Sliding Window Counter algorithm:

1. **Read Requirements**: `.kiro/specs/sliding-window-counter/requirements.md`
2. **Review Design**: `.kiro/specs/sliding-window-counter/design.md`
3. **Follow Implementation Plan**: `.kiro/specs/sliding-window-counter/impl.md`
4. **Execute Tasks**: Work through the task checklist step-by-step