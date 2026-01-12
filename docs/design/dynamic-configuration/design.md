# Dynamic Configuration Management - Technical Design

## Architecture

Redis storage → local cache → policy resolution.

```
┌─────────────────────────────────────────────────────────────┐
│                    Management API                           │
│  ConfigController │ PolicyValidator │ AuditLogger          │
└─────────────────────────────────┬───────────────────────────┘
                                  │
┌─────────────────────────────────▼───────────────────────────┐
│                  Redis + Local Cache                       │
│     Redis Policies     │     LRU Cache (1000 entries)     │
└─────────────────────────────────┬───────────────────────────┘
                                  │
┌─────────────────────────────────▼───────────────────────────┐
│                 Rate Limiter Service                       │
│  ConfigResolver │ PatternMatcher │ StrategySelector        │
└─────────────────────────────────────────────────────────────┘
```

## Data Model

### Policy Structure
```json
{
  "id": "emergency-payment-001",
  "priority": 200,
  "keyPattern": "api:payment:*", 
  "algorithm": "token_bucket",
  "limit": 50,
  "window": "60s"
}
```

### Redis Storage
- Key: `config:policy:{id}`
- TTL: 24h (prevent leaks)
- Pub/sub: `config:changes`
- Event: `{type: "policy_updated", id: "policy-123"}`

## Components

### Policy Resolution

```java
@Component
public class ConfigurationResolver {
    
    public RateLimitConfig resolveConfig(RateLimitRequest request) {
        // Skip if hardcoded (backward compatibility)
        if (request.hasHardcodedLimits()) {
            return buildHardcodedConfig(request);
        }
        
        // Check cache first
        List<Policy> policies = localCache.getPolicies(request.getKey());
        if (policies == null) {
            policies = fetchFromRedis(request.getKey());
            localCache.put(request.getKey(), policies, 30_SECONDS);
        }
        
        // Find best match
        return policies.stream()
            .filter(p -> patternMatcher.matches(p.getKeyPattern(), request.getKey()))
            .max(Comparator.comparing(Policy::getPriority))
            .map(p -> buildConfig(p))
            .orElse(getDefaultConfig());
    }
}
```

### Pattern Matching

```java
@Component  
public class PatternMatcher {
    
    public boolean matches(String pattern, String key) {
        if (pattern.equals(key)) return true;
        
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return key.startsWith(prefix);
        }
        
        return false;
    }
}
```

**Decision**: Simple wildcard only. Regex adds complexity and performance risk.

### Local Cache

```java
@Component
public class PolicyCache {
    private final Cache<String, List<Policy>> cache = 
        Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();
    
    @EventListener
    public void onConfigChange(ConfigChangeEvent event) {
        cache.invalidateAll(event.getAffectedKeys());
    }
}
```

**Strategy**: 1000 entries, 30s TTL, pub/sub invalidation.

### Management API

```java
@RestController
@RequestMapping("/admin/policies")
public class PolicyController {
    
    @PostMapping
    public ResponseEntity<Policy> createPolicy(@Valid @RequestBody Policy policy,
                                             @RequestHeader("X-Admin-Key") String apiKey) {
        validateApiKey(apiKey);
        validatePolicy(policy);
        
        policyRepository.save(policy);
        publishChangeEvent(policy.getId());
        auditLogger.logCreate(policy, getUserFromApiKey(apiKey));
        
        return ResponseEntity.status(201).body(policy);
    }
}
```

**Auth**: Single admin API key (application.properties).

## Design Decisions

### Why Redis over alternatives

**Database**: Too slow (<1ms requirement)
**Separate service**: Adds latency and failure modes
**File config**: Requires deployments
**Consul/etcd**: Operational complexity

### Why simple patterns

**Regex**: Performance risk, debugging complexity
**Wildcards**: Covers 90% of use cases, predictable performance

### Why local cache

**Performance**: Sub-ms lookup for hot paths
**Reliability**: Graceful degradation when Redis down
**Efficiency**: Reduces Redis load

## Error Handling

### Redis Unavailable
```java
try {
    policies = redisTemplate.opsForValue().get(key);
} catch (Exception e) {
    logger.warn("Redis unavailable, using cache", e);
    policies = localCache.getIfPresent(key);
    return policies != null ? policies : getDefaultConfig();
}
```

### Invalid Data
```java
try {
    policy = objectMapper.readValue(json, Policy.class);
    validatePolicy(policy);
} catch (Exception e) {
    logger.error("Invalid policy, skipping", e);
    return null;
}
```

## Performance

### Latency
- Cache hit: <0.1ms
- Cache miss: <2ms (Redis lookup)
- Pattern match: O(1) exact, O(n) wildcard

### Memory
- Cache: 1000 entries max
- Policy size: ~200 bytes each
- Total overhead: <200KB

### Scalability
- Stateless service
- Redis bottleneck at ~50K ops/sec
- Horizontal scaling via instances

## Security

### Authentication
- API key in `X-Admin-Key` header
- Key in encrypted application.properties
- 401 for invalid/missing

### Validation
- JSON schema for structure
- Pattern validation (prevent injection)
- Limit bounds checking

### Audit
```java
auditLogger.info("Policy created: id={}, user={}, pattern={}", 
    policy.getId(), user, policy.getKeyPattern());
```

## Monitoring

### Metrics
- `config.lookup.duration` - Resolution latency
- `config.cache.hit_ratio` - Cache effectiveness
- `config.changes.total` - Change frequency
- `config.errors.total` - Error rates

### Health
```java
@Component
public class ConfigHealthIndicator implements HealthIndicator {
    public Health health() {
        try {
            redisTemplate.opsForValue().get("health");
            return Health.up().withDetail("redis", "available").build();
        } catch (Exception e) {
            return Health.down().withException(e).build();
        }
    }
}
```

## Non-goals

**Phase 1 limitations**:
- Single API key (no RBAC)
- No versioning (delete/recreate)
- No time-based rules
- No regex patterns
- No A/B testing

**Rationale**: Prove core concept before adding complexity.

## Rollout Plan

### Feature Flag
`dynamic.config.enabled=false` (default off)

### Phases
1. Deploy disabled
2. Enable non-critical services
3. Monitor performance/errors
4. Gradual rollout
5. Emergency disable if needed

### Rollback
1. Set flag false
2. Restart services
3. Falls back to hardcoded
4. Fix issues
5. Re-enable

## Risks

### Performance Impact
**Risk**: Policy lookup slows requests
**Mitigation**: Local caching, load testing, feature flag

### Redis Dependency
**Risk**: More critical Redis dependency
**Mitigation**: Graceful degradation, cache fallback

### Config Errors
**Risk**: Invalid policies break rate limiting
**Mitigation**: Validation, audit logging, rollback capability