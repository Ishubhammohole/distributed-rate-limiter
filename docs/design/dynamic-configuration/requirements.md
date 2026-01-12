# Dynamic Configuration Management - Requirements

## Problem

Q4 payment incident: couldn't reduce rate limits fast enough. Service degraded for 45 minutes waiting for deployment.

Current state:
- Emergency changes require deployments
- No centralized policy view
- Hardcoded limits in requests
- Cannot test algorithms without code changes

## Solution

Redis-backed policy engine. Check Redis before hardcoded defaults.

Goal: Ops update limits in Redis, propagate within 30 seconds.

## MVP Requirements

### Functional

**Policy Storage**:
- Store policies in Redis (simple JSON)
- Basic CRUD via REST API
- Atomic updates using Lua scripts
- TTL cleanup

**Pattern Matching**:
- Exact match: `api:payment:checkout`
- Wildcard: `api:payment:*`
- Priority resolution (higher wins)

**Real-time Updates**:
- Redis pub/sub notifications
- Local cache invalidation <5s
- Graceful Redis degradation
- Backward compatibility

**Management API**:
- REST CRUD endpoints
- API key authentication
- JSON validation
- Audit logging

### Non-Functional

**Performance**:
- <1ms policy lookup (P99)
- 10K RPS support
- <10% memory increase

**Reliability**:
- Redis down → use cache/defaults
- Atomic updates
- Zero breaking changes

**Security**:
- API key auth
- Input validation
- Audit trail

## Non-goals

- A/B testing (complex, unclear ROI)
- Time-based policies (edge case)
- Regex patterns (performance risk)
- Advanced dashboards (existing sufficient)
- RBAC (single key sufficient)
- Policy versioning (delete/recreate OK)

## Constraints

**Infrastructure**:
- Use existing Redis
- No new dependencies
- Integrate with current monitoring

**Compatibility**:
- Hardcoded requests unchanged
- Dynamic only when no hardcoded limits
- Feature flag for disable

## Success Criteria

MVP complete when:
- Change limits via API <30s
- <1ms performance impact (P99)
- Zero breaking changes
- Graceful Redis degradation

## Rollout Plan

**Feature flag**: `dynamic.config.enabled=false` (default)

**Phases**:
1. Deploy disabled
2. Enable non-critical services
3. Monitor performance
4. Gradual rollout
5. Emergency disable if needed

**Rollback**: Flag to false, restart, hardcoded fallback

## Risks

**High**:
- Performance impact on hot path
- Redis dependency criticality
- Config errors breaking limits

**Mitigation**:
- Load testing
- Feature flag
- Input validation
- Graceful fallback