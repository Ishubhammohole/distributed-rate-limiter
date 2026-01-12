# Dynamic Configuration Management

## Problem

Rate limiter requires deployments to change limits. During Q4 payment incident, couldn't reduce limits fast enough - ops waited 45 minutes for deployment while service degraded.

Pain points:
- Emergency rate limit changes require deployments
- No centralized policy management
- Each service hardcodes limits in requests
- Cannot test different algorithms without code changes

## Solution

Redis-backed policy engine that checks Redis before falling back to hardcoded limits.

Core capability: Ops update limits in Redis, propagate via pub/sub within 30 seconds.

## Decision: Redis Storage

**Chosen**: Redis with local caching
- Already depend on Redis for rate limiting state
- Sub-millisecond lookup with local cache
- Atomic updates via existing Lua scripts
- No new infrastructure dependencies

**Rejected alternatives**:
- Separate config service: adds latency + failure mode
- Database-backed: too slow for hot path
- File-based: requires deployments
- Consul/etcd: operational complexity

## Scope

### MVP / In Scope
Emergency rate limit changes without deployments.

Components:
- Redis policy storage (simple JSON)
- Pattern matching (exact + wildcard only)
- Basic CRUD API with API key auth
- Cache invalidation via pub/sub
- Backward compatibility

Success criteria:
- Change limits in <30 seconds during incidents
- <1ms latency impact (P99)
- Zero breaking changes

### Non-goals
- A/B testing (complex, unclear ROI)
- Time-based policies (edge case)
- Regex patterns (performance risk)
- Advanced dashboards (existing metrics sufficient)
- Multi-region sync (YAGNI)

### Future Work
- **Gradual rollouts**: if demand proven after MVP
- **Advanced patterns**: regex support with performance validation
- **Time-based rules**: scheduled policy activation
- **Enhanced monitoring**: custom dashboards and alerting

## Constraints

**Performance**:
- <1ms policy lookup latency (P99)
- Support 10K RPS without degradation
- <10% memory increase

**Reliability**:
- Graceful degradation when Redis down
- Atomic policy updates
- Backward compatibility

**Security**:
- API key authentication
- Audit logging
- Input validation

## Rollout Plan

**Feature flag**: `dynamic.config.enabled=false` (default off)

**Phases**:
1. Deploy with feature disabled
2. Enable for non-critical services
3. Monitor performance/errors
4. Gradual rollout to all services
5. Emergency disable via flag if needed

**Rollback**: Set flag to false, restart services, falls back to hardcoded behavior

## Implementation

**Timeline**: 4 weeks
- Week 1: Redis storage + models
- Week 2: Policy resolution + caching
- Week 3: Management API + auth
- Week 4: Integration + testing

**Risk mitigation**:
- Feature flag for emergency disable
- Load testing before rollout
- Start with non-critical services