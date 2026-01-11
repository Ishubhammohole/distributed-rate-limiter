-- Sliding Window Counter Rate Limiter with Window-Scoped Keys
-- Implements memory-efficient approximate rate limiting using window-scoped Redis keys
-- that automatically handle window transitions without complex rotation logic.

-- Input Parameters:
-- KEYS[1] = currentWindowKey - Redis key for current window (includes window timestamp)
-- KEYS[2] = previousWindowKey - Redis key for previous window (includes window timestamp)
-- ARGV[1] = limit - Rate limit threshold (number)
-- ARGV[2] = cost - Request cost, typically 1 (number)
-- ARGV[3] = nowMillis - Current timestamp in milliseconds (number)
-- ARGV[4] = windowSizeMillis - Window size in milliseconds (number)
-- ARGV[5] = ttlSeconds - TTL for counter keys (number)

local currentWindowKey = KEYS[1]
local previousWindowKey = KEYS[2]
local limit = tonumber(ARGV[1])
local cost = tonumber(ARGV[2])
local nowMillis = tonumber(ARGV[3])
local windowSizeMillis = tonumber(ARGV[4])
local ttlSeconds = tonumber(ARGV[5])

-- Validate input parameters
if not limit or limit <= 0 then
    return redis.error_reply("Invalid limit: must be positive number")
end
if not cost or cost <= 0 then
    return redis.error_reply("Invalid cost: must be positive number")
end
if not nowMillis or nowMillis <= 0 then
    return redis.error_reply("Invalid nowMillis: must be positive number")
end
if not windowSizeMillis or windowSizeMillis <= 0 then
    return redis.error_reply("Invalid windowSizeMillis: must be positive number")
end
if not ttlSeconds or ttlSeconds <= 0 then
    return redis.error_reply("Invalid ttlSeconds: must be positive number")
end

-- Get current and previous window counters (simple integers now)
local currentCount = tonumber(redis.call('GET', currentWindowKey) or 0)
local previousCount = tonumber(redis.call('GET', previousWindowKey) or 0)

-- Calculate previous window weight based on time overlap
-- weight = (windowSize - timeIntoCurrentWindow) / windowSize
local currentWindowStart = math.floor(nowMillis / windowSizeMillis) * windowSizeMillis
local timeIntoCurrentWindow = nowMillis - currentWindowStart
local weight = (windowSizeMillis - timeIntoCurrentWindow) / windowSizeMillis

-- Clamp weight to [0, 1] range
if weight < 0 then
    weight = 0
elseif weight > 1 then
    weight = 1
end

-- Calculate weighted contribution from previous window
local weightedPreviousCount = math.floor(previousCount * weight)

-- Calculate total estimated request count
local estimatedCount = currentCount + weightedPreviousCount

-- Check if request can be allowed
if estimatedCount + cost <= limit then
    -- Allow request: increment current window counter
    local newCurrentCount = redis.call('INCRBY', currentWindowKey, cost)
    redis.call('EXPIRE', currentWindowKey, ttlSeconds)
    
    -- Ensure previous window key exists with TTL (but don't modify its value)
    if redis.call('EXISTS', previousWindowKey) == 1 then
        redis.call('EXPIRE', previousWindowKey, ttlSeconds)
    end
    
    -- Calculate remaining requests
    local remaining = limit - (newCurrentCount + weightedPreviousCount)
    if remaining < 0 then
        remaining = 0
    end
    
    -- Return: [allowed=1, remaining, currentCount, previousCount, weight]
    return {1, remaining, newCurrentCount, previousCount, weight}
else
    -- Rate limited: do not increment counter
    local remaining = limit - estimatedCount
    if remaining < 0 then
        remaining = 0
    end
    
    -- Calculate retry after in seconds
    local retryAfterMillis = currentWindowStart + windowSizeMillis - nowMillis
    local retryAfterSeconds = math.ceil(retryAfterMillis / 1000)
    
    -- Return: [allowed=0, remaining, currentCount, previousCount, weight, retryAfterSeconds]
    return {0, remaining, currentCount, previousCount, weight, retryAfterSeconds}
end