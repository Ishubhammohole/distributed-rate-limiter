# Dynamic Configuration Management - Implementation Tasks

## MVP Implementation (4 weeks)

Goal: Emergency rate limit changes without deployments.

---

## Week 1: Storage Foundation

### Task 1.1: Redis Policy Storage
**Effort**: 3 days

- [ ] 1.1.1 Policy domain model
  - JSON structure: `{id, priority, keyPattern, algorithm, limit, window}`
  - Jackson serialization + validation
  - No versioning/time features

- [ ] 1.1.2 Redis repository
  - Key: `config:policy:{id}`
  - Atomic updates via Lua
  - TTL cleanup (24h)

- [ ] 1.1.3 Change events
  - Pub/sub: `config:changes`
  - Event: `{type, id}`
  - Reuse Redis connection

### Task 1.2: Policy Resolution
**Effort**: 2 days

- [ ] 1.2.1 Pattern matcher
  - Exact + wildcard (`api:user:*`)
  - Priority resolution
  - No regex

- [ ] 1.2.2 Local cache
  - LRU 1000 entries
  - 30s TTL + pub/sub invalidation
  - Graceful fallback

---

## Week 2: Service Integration

### Task 2.1: RateLimiterService Integration
**Effort**: 3 days

- [ ] 2.1.1 ConfigurationResolver
  - Cache → Redis → defaults
  - Skip when hardcoded limits
  - Same API contract

- [ ] 2.1.2 Strategy factory update
  - Use resolved config
  - Feature flag `dynamic.config.enabled=false`
  - Metrics (hit/miss/error)

### Task 2.2: Error Handling
**Effort**: 2 days

- [ ] 2.2.1 Graceful degradation
  - Redis timeout → cache
  - Cache miss → defaults
  - Invalid policy → log + skip

---

## Week 3: Management API

### Task 3.1: CRUD API
**Effort**: 3 days

- [ ] 3.1.1 ConfigurationController
  - `GET/POST/PUT/DELETE /admin/policies`
  - JSON validation
  - Conflict detection

- [ ] 3.1.2 Validation
  - Schema validation
  - Business rules
  - Dry-run endpoint

### Task 3.2: Auth & Audit
**Effort**: 2 days

- [ ] 3.2.1 API key auth
  - `X-Admin-Key` header
  - Single key in properties
  - 401 for invalid

- [ ] 3.2.2 Audit logging
  - Structured logs
  - User + action + policy
  - Existing log infrastructure

---

## Week 4: Testing & Integration

### Task 4.1: Testing
**Effort**: 3 days

- [ ] 4.1.1 Unit tests
  - Policy resolution scenarios
  - Cache behavior
  - Error handling

- [ ] 4.1.2 Integration tests
  - End-to-end with Redis
  - Multi-instance propagation
  - Performance (<1ms)

### Task 4.2: Production Prep
**Effort**: 2 days

- [ ] 4.2.1 Load testing
  - Baseline vs dynamic config
  - 100 policies + 10K RPS
  - Memory analysis

- [ ] 4.2.2 Operations
  - Feature flag procedures
  - Rollback runbook
  - Basic alerts

---

## Success Criteria

MVP complete when:
- [ ] Create/update policies <10s
- [ ] Changes propagate <30s
- [ ] <1ms performance impact (P99)
- [ ] Zero breaking changes
- [ ] Feature flag disable works
- [ ] All changes audited

## Non-goals

Not in MVP:
- A/B testing
- Time-based policies
- Regex patterns
- Advanced dashboards
- RBAC
- Policy versioning

Rationale: Prove core concept before complexity.

## Risk Mitigation

**Performance**: Load testing, feature flag
**Redis dependency**: Caching, graceful degradation
**Config errors**: Validation, audit, rollback