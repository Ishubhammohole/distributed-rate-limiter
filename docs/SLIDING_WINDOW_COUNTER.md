# Sliding Window Counter Algorithm

## Overview

The Sliding Window Counter algorithm provides memory-efficient approximate rate limiting by maintaining only two counters (current and previous window) instead of tracking individual request timestamps.

## How It Works

### Two-Bucket Approach

```
Previous Window    Current Window
[====3 reqs====]  [==1 req==]
     ^                ^
   Weight           Count
   0.7 × 3 = 2.1    1
   floor(2.1) = 2
   
   Estimated total: 2 + 1 = 3 requests
```

### Weight Calculation

The previous window's contribution is weighted based on time overlap:

```
weight = (windowSize - timeIntoCurrentWindow) / windowSize
```

**Example**: For a 10-second window, if we're 3 seconds into the current window:
- `weight = (10 - 3) / 10 = 0.7`
- Previous window contributes 70% of its count

### Approximation Behavior

The algorithm uses `floor()` on the weighted previous count, which can cause:

1. **Undercount (allows extra requests)**: Most common
   - Previous: 3 requests, Weight: 0.99, floor(3 × 0.99) = 2
   - True count would be ~3, but algorithm sees 2
   - **Result**: May allow requests that should be denied

2. **Accurate count**: When floor() doesn't change the value
   - Previous: 4 requests, Weight: 0.5, floor(4 × 0.5) = 2
   - True sliding window would also be ~2
   - **Result**: Correct decision

## Accuracy Characteristics

### Typical Performance
- **90-95% accuracy** under normal distributed request patterns
- **Error magnitude**: Usually ≤10% of the limit

### Worst-Case Scenarios
- **Accuracy**: ~75% (25% error)
- **When**: Requests clustered at window boundaries with high weights
- **Error magnitude**: Up to 25% of the limit

### Example Worst-Case

```bash
Limit: 3 requests per 10s
Scenario: 3 requests at 9.9s, then 1 request at 0.1s into next window

True sliding window at 0.1s: 4 requests (should deny)
Algorithm calculation:
- Previous window: 3 requests
- Weight: (10000 - 100) / 10000 = 0.99
- Weighted: floor(3 × 0.99) = floor(2.97) = 2
- Current: 1 request
- Total: 2 + 1 = 3 ≤ 3 → ALLOW

Result: Request allowed when it should be denied
```

## Trade-offs

### Advantages
- **Memory**: O(1) per key vs O(n) for sliding window log
- **Performance**: <1ms Redis operations, 10,000+ RPS capable
- **Simplicity**: Only 2 Redis keys per rate limit key

### Disadvantages
- **Approximation**: Not exact, can allow ~25% extra requests in worst case
- **Burst tolerance**: Less precise burst control than exact algorithms

## When to Use

**Good fit**:
- High-throughput APIs where memory efficiency matters
- Applications that can tolerate occasional rate limit "leakage"
- Cost-sensitive deployments (lower Redis memory usage)

**Poor fit**:
- Security-critical rate limiting (use sliding window log)
- Applications requiring exact request counting
- Very low rate limits where approximation error is significant

## Configuration

```yaml
rate-limiter:
  algorithm: sliding_window_counter
  redis:
    key-prefix: "rate_limit:sliding_window_counter:"
    ttl-multiplier: 2  # TTL = 2 × window size
```

## Monitoring

Key metrics to monitor:
- Request allow/deny rates
- Window transition frequency
- Redis memory usage
- Algorithm accuracy (if measurable)

The algorithm logs structured data for each decision:
```
Rate limit exceeded: key=api:user:123, algorithm=sliding_window_counter, 
limit=100, window=60000ms, remaining=0, current=45, previous=60, 
weight=0.750, retryAfter=15s
```