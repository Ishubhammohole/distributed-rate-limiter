-- Sliding Window Counter Rate Limiter
-- Implements memory-efficient approximate rate limiting using current and previous window counters
-- with time-based weighting for smooth rate limiting across window boundaries.

-- Input Parameters:
-- KEYS[1] = currentWindowKey - Redis key for current window counter
-- KEYS[2] = previousWindowKey - Redis key for previous window counter
-- ARGV[1] = limit - Rate limit threshold (number)
-- ARGV[2] = cost - Request cost, typically 1 (number)
-- ARGV[3] = currentWindow - Current window identifier (number)
-- ARGV[4] = previousWindow - Previous window identifier (number)  
-- ARGV[5] = previousWindowWeight - Weight factor for previous window 0.0-1.0 (number)
-- ARGV[6] = ttlSeconds - TTL for counter keys (number)

local currentWindowKey = KEYS[1]
local previousWindowKey = KEYS[2]
local limit = tonumber(ARGV[1])
local cost = tonumber(ARGV[2])
local currentWindow = tonumber(ARGV[3])
local previousWindow = tonumber(ARGV[4])
local previousWindowWeight = tonumber(ARGV[5])
local ttlSeconds = tonumber(ARGV[6])

-- Validate input parameters
if not limit or limit <= 0 then
    return redis.error_reply("Invalid limit: must be positive number")
end
if not cost or cost <= 0 then
    return redis.error_reply("Invalid cost: must be positive number")
end
if not previousWindowWeight or previousWindowWeight < 0 or previousWindowWeight > 1 then
    return redis.error_reply("Invalid previousWindowWeight: must be between 0.0 and 1.0")
end
if not ttlSeconds or ttlSeconds <= 0 then
    return redis.error_reply("Invalid ttlSeconds: must be positive number")
end

-- Get current window counter (default to 0 if not exists)
local currentCount = tonumber(redis.call('GET', currentWindowKey) or 0)

-- Get previous window counter (default to 0 if not exists)
local previousCount = tonumber(redis.call('GET', previousWindowKey) or 0)

-- Calculate weighted contribution from previous window
-- Use math.floor to ensure integer arithmetic and avoid floating point precision issues
local weightedPreviousCount = math.floor(previousCount * previousWindowWeight)

-- Calculate total weighted request count
local totalCount = currentCount + weightedPreviousCount

-- Check if request can be allowed (including the new request cost)
if totalCount + cost <= limit then
    -- Allow request: increment current window counter
    local newCurrentCount = redis.call('INCRBY', currentWindowKey, cost)
    
    -- Set TTL on current window key (refresh on each update)
    redis.call('EXPIRE', currentWindowKey, ttlSeconds)
    
    -- Ensure previous window key exists and has proper TTL
    -- This handles the case where previous window doesn't exist yet
    if redis.call('EXISTS', previousWindowKey) == 0 then
        redis.call('SET', previousWindowKey, 0)
    end
    redis.call('EXPIRE', previousWindowKey, ttlSeconds)
    
    -- Calculate remaining requests in current window
    -- remaining = limit - (newCurrentCount + weightedPreviousCount)
    local remaining = limit - (newCurrentCount + weightedPreviousCount)
    
    -- Ensure remaining is not negative (defensive programming)
    if remaining < 0 then
        remaining = 0
    end
    
    -- Return: [allowed=1, remaining, currentCount, previousCount]
    return {1, remaining, newCurrentCount, previousCount}
else
    -- Rate limited: do not increment counter
    local remaining = limit - totalCount
    
    -- Ensure remaining is not negative
    if remaining < 0 then
        remaining = 0
    end
    
    -- Return: [allowed=0, remaining, currentCount, previousCount]
    return {0, remaining, currentCount, previousCount}
end