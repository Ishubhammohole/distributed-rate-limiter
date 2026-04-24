# Performance Regression Analysis

## Scope

This analysis compares the known good benchmark snapshot `1dc2a93` with the later regression path around `6cd2181`, records the fixes applied in this pass, and documents the exact bottleneck that still prevents the local Docker benchmark from matching the older headline numbers.

## Root Cause

Two different problems were mixed together:

1. The real regression from `6cd2181`
   - profiling-related request work was added to the benchmark runtime path
   - benchmark scenarios reused a distressed JVM between runs
   - Redis client settings added extra checkout overhead
2. The older 5K headline was not reproducible once the benchmark was made cleaner
   - under an isolated `constant-arrival-rate` run with `maxVUs=1000`, the service still saturates before `5000 req/s`

The regression itself was real and fixable. The remaining gap is a separate throughput limit.

## Fixes Applied

### Hot path cleanup

- Removed the profiling filter, interceptor, holder, and conditional profiling config from the default runtime.
- Restored the request path to one Redis Lua execution per rate-limit check.
- Kept request logging out of the benchmark path.
- Cached `DefaultRedisScript<List>` instances in `LuaScriptExecutor` instead of recreating them on every request.
- Removed per-request stack traces from strategy failure logging so timeout bursts do not turn into a logging amplification loop.

### Redis/Lettuce changes

- Disabled the shared native Lettuce connection so requests can borrow real pooled connections under load.
- Tuned the Tomcat thread pool higher for the blocking Spring MVC request path.
- Tested wider Redis pool settings after proving the original pool was starving.

### Benchmark isolation

- Restarted Redis and the service between scenarios.
- Flushed Redis between warmup and measured runs.
- Captured per-scenario Docker stats, service logs, Redis logs, and Redis INFO output.

## Evidence

### 1. The original regression was fixed

The worst `unique_keys_1k` collapse from the regressed path was:

- `426.46 req/s`
- `p95 ~5.0s`
- `28.37%` HTTP errors

After removing profiling overhead and isolating scenarios, the same scenario at a stable `3000 req/s` target improved to:

- `2903.41 req/s`
- `p95 372.87 ms`
- `p99 562.57 ms`
- `0.00%` HTTP errors

That proves the regression itself was real and was materially reduced.

### 2. Redis is not the main CPU bottleneck at 5K

During the stressed `unique_keys_1k` run:

- service CPU was about `225%`
- Redis CPU was about `1.14%`
- Redis `EVALSHA` time was about `94.10 us/call`

Relevant captured artifacts:

- `benchmark/results/unique_keys_1k-after-docker-stats.json`
- `benchmark/results/unique_keys_1k-after-redis-info-commandstats.txt`
- `benchmark/results/unique_keys_1k-after-redis-info-stats.txt`

That means Redis command execution itself stayed cheap while the application path saturated first.

### 3. The exact bottleneck is Redis connection borrowing in the blocking request path

When the service was run on the host JVM with pooled dedicated Lettuce connections, the logs showed:

- `PoolException: Could not get a resource from the pool`
- `Timeout waiting for idle object, borrowMaxWaitDuration=PT0.1S`

This came from the synchronous servlet path borrowing Redis connections faster than the configured pool could recycle them under the 5K target. Once that happened, requests queued, latencies rose, and k6 hit its `1000` VU ceiling.

### 4. Dedicated pooled connections helped, but did not restore the old headline

Best observed stressed `unique_keys_1k` result after enabling dedicated pooled Lettuce connections:

- `1538.79 req/s`
- `p95 1.43 s`
- `p99 5.00 s`
- `2.17%` HTTP errors

That was better than the earlier stressed run at:

- `1120.53 req/s`
- `p95 1.85 s`
- `0.01%` HTTP errors

So the change moved the bottleneck in the right direction, but not far enough to hit the old `1dc2a93` numbers.

## Assessment

### What was successfully fixed

- profiling overhead is no longer distorting the benchmark path
- scenario contamination was removed
- the `unique_keys_1k` regression no longer collapses at the stable `3000 req/s` target
- the investigation now has concrete, defensible evidence instead of guesswork

### What was not restored

The older headline baseline was not restored on this machine under the cleaned-up Docker benchmark:

- `unique_keys_1k >= 3500 req/s` was not met
- `mixed_traffic >= 3000 req/s` was not met
- `hot_key >= 2500 req/s` was not re-verified after the latest isolation/tuning pass

## Conclusion

The latest pass fixed the regression, but it also proved the current architecture's next limit:

- Spring MVC request handling is synchronous
- every request blocks on a Redis connection borrow plus one Lua script call
- under `5000 req/s` with `1000` max VUs, the app saturates before Redis CPU does

Restoring the old `1dc2a93` headline in this environment would require a deeper concurrency change, such as reworking the blocking servlet + synchronous Redis access model. That is outside the "optimize without rewriting the system" constraint for this pass.
