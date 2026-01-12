# Dynamic Configuration Management - Acceptance Criteria

## MVP Scope

Basic policy CRUD, pattern matching, cache invalidation, backward compatibility.

---

## Core Functionality

### AC-1: Policy Storage
Goal: Store/retrieve policies reliably

#### AC-1.1: Basic CRUD
**Given** valid policy JSON  
**When** created via POST /admin/policies  
**Then** stored in Redis and retrievable  

```bash
POST /admin/policies
{
  "id": "emergency-001", 
  "priority": 100,
  "keyPattern": "api:payment:*",
  "algorithm": "token_bucket",
  "limit": 50,
  "window": "60s"
}

GET /admin/policies/emergency-001
# Returns exact policy
```

**Success**: 201 created, exact retrieval, Redis storage

#### AC-1.2: Updates/Deletion
**Given** existing policy  
**When** updated or deleted  
**Then** changes reflected immediately  

**Success**: PUT atomic, DELETE removes, cache invalidated

### AC-2: Pattern Matching
Goal: Find correct policy per request

#### AC-2.1: Priority Resolution
**Given** overlapping policies  
**When** request processed  
**Then** highest priority wins  

```bash
Policy A: keyPattern="api:*", priority=100, limit=100
Policy B: keyPattern="api:payment:*", priority=200, limit=50

Request: key="api:payment:checkout" → Uses Policy B (limit=50)
```

**Success**: Specific beats general, higher priority wins, exact beats wildcard

#### AC-2.2: Backward Compatibility
**Given** hardcoded requests  
**When** dynamic config enabled  
**Then** hardcoded unchanged  

```bash
# Hardcoded (ignores policies)
POST /api/v1/ratelimit/check
{"key": "api:user:123", "limit": 100, "window": "60s"}

# Dynamic (uses policies)  
POST /api/v1/ratelimit/check
{"key": "api:user:123"}
```

**Success**: Hardcoded precedence, no breaking changes

### AC-3: Real-time Updates
Goal: Fast propagation across instances

#### AC-3.1: Propagation
**Given** multiple instances  
**When** policy updated  
**Then** all see change <30s  

**Success**: 30s propagation, pub/sub works, eventual consistency

---

## Performance

### AC-4: Latency Impact
Goal: Minimal performance hit

#### AC-4.1: Lookup Performance
**Given** rate limit requests  
**When** policy resolution performed  
**Then** <1ms impact (P99)  

**Test**: 10K RPS, 5min, compare with/without dynamic config

**Success**: P99 <1ms increase, >90% cache hit, <10% memory increase

---

## Reliability

### AC-5: Failure Handling
Goal: Graceful degradation

#### AC-5.1: Redis Down
**Given** Redis unavailable  
**When** policy resolution attempted  
**Then** graceful fallback  

**Success**: Service continues, uses cache/defaults, recovers automatically

#### AC-5.2: Invalid Config
**Given** invalid policy data  
**When** system loads it  
**Then** handles gracefully  

**Success**: Validation prevents, corruption triggers fallback, stable system

---

## Security

### AC-6: API Authentication
Goal: Secure admin access

#### AC-6.1: Key Auth
**Given** admin endpoints  
**When** accessed without key  
**Then** 401 Unauthorized  

```bash
GET /admin/policies → 401
GET /admin/policies (X-Admin-Key: invalid) → 401  
GET /admin/policies (X-Admin-Key: valid) → 200
```

**Success**: Auth required, invalid rejected, valid allowed

### AC-7: Audit Trail
Goal: Track changes

#### AC-7.1: Change Logging
**Given** policy modification  
**When** change made  
**Then** logged with context  

**Success**: All CRUD logged, structured format, timestamp/user/action

---

## Operations

### AC-8: Feature Flag
Goal: Safe rollout/rollback

#### AC-8.1: Toggle Control
**Given** `dynamic.config.enabled` flag  
**When** set false  
**Then** hardcoded behavior only  

**Success**: Disabled by default, runtime toggle, clean fallback

### AC-9: Monitoring
Goal: System visibility

#### AC-9.1: Key Metrics
**Given** system running  
**When** checking metrics  
**Then** see resolution stats  

**Metrics**: Cache hit/miss, latency, change frequency, errors

**Success**: Available in monitoring, alerts for errors, health checks

---

## MVP Complete When

- [ ] Create/update policies via API
- [ ] Changes propagate <30s
- [ ] <1ms performance impact (P99)
- [ ] Zero breaking changes
- [ ] Feature flag disable works
- [ ] All changes audited
- [ ] Graceful Redis degradation

## Non-goals

Not in MVP:
- A/B testing
- Time-based policies
- Regex patterns
- Advanced dashboards
- RBAC
- Policy versioning

Rationale: Prove concept before complexity.