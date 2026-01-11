-- Sliding Window Counter Rate Limiter with Window-Aware Rotation
-- Implements memory-efficient approximate rate limiting using current and previous window counters
-- with time-based weighting and automatic window rotation for correct boundary handling.

-- Input Parameters:
-- KEYS[1] = currentWindowKey - Redis key for current window counter
-- KEYS[2] = previousWindowKey - Redis key for previous window counter
-- ARGV[1] = limit - Rate limit threshold (number)
-- ARGV[2] = cost - Request cost, typically 1 (number)
-- ARGV[3] = nowMillis - Current timestamp in milliseconds (number)
-- ARGV[4] = windowSizeMillis - Window size in milliseconds (number)
-- ARGV[5] = currentWindowStart - Current window start timestamp (number)
-- ARGV[6] = previousWindowStart - Previous window start timestamp (number)
-- ARGV[7] = ttlSeconds - TTL for counter keys (number)

local currentWindowKey = KEYS[1]
local previousWindowKey = KEYS[2]
local limit = tonumber(ARGV[1])
local cost = tonumber(ARGV[2])
local nowMillis = tonumber(ARGV[3])
local windowSizeMillis = tonumber(ARGV[4])
local currentWindowStart = tonumber(ARGV[5])
local previousWindowStart = tonumber(ARGV[6])
local ttlSeconds = tonumber(ARGV[7])

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

-- Helper function to parse window value: "windowStart:count" -> {windowStart, count}
local function parseWindowValue(value, defaultWindowStart, defaultCount)
    if not value or value == "" then
        return defaultWindowStart, defaultCount
    end
    local colonPos = string.find(value, ":")
    if not colonPos then
        -- Legacy format: just count
        return defaultWindowStart, tonumber(value) or defaultCount
    end
    local windowStart = tonumber(string.sub(value, 1, colonPos - 1)) or defaultWindowStart
    local count = tonumber(string.sub(value, colonPos + 1)) or defaultCount
    return windowStart, count
end

-- Helper function to format window value: {windowStart, count} -> "windowStart:count"
local function formatWindowValue(windowStart, count)
    return windowStart .. ":" .. count
end

-- Get current and previous window values
local currentValue = redis.call('GET', currentWindowKey)
local previousValue = redis.call('GET', previousWindowKey)

-- Parse current window: windowStart:count
local curStart, curCount = parseWindowValue(currentValue, currentWindowStart, 0)

-- Parse previous window: windowStart:count  
local prevStart, prevCount = parseWindowValue(previousValue, previousWindowStart, 0)

-- Handle window rotation if current window has changed
if curStart ~= currentWindowStart then
    if curStart == previousWindowStart then
        -- Advanced by exactly 1 window: rotate current -> previous
        prevStart = curStart
        prevCount = curCount
    else
        -- Jumped more than 1 window: reset previous
        prevStart = previousWindowStart
        prevCount = 0
    end
    -- Reset current window
    curStart = currentWindowStart
    curCount = 0
end

-- Handle previous window reset if it doesn't match expected
if prevStart ~= previousWindowStart then
    prevStart = previousWindowStart
    prevCount = 0
end

-- Calculate previous window weight based on time overlap
-- weight = (windowSize - timeIntoCurrentWindow) / windowSize
local timeIntoCurrentWindow = nowMillis - currentWindowStart
local weight = (windowSizeMillis - timeIntoCurrentWindow) / windowSizeMillis

-- Clamp weight to [0, 1] range
if weight < 0 then
    weight = 0
elseif weight > 1 then
    weight = 1
end

-- Calculate weighted contribution from previous window
local weightedPreviousCount = math.floor(prevCount * weight)

-- Calculate total estimated request count
local estimatedCount = curCount + weightedPreviousCount

-- Check if request can be allowed
if estimatedCount + cost <= limit then
    -- Allow request: increment current window counter
    curCount = curCount + cost
    
    -- Persist updated window values
    redis.call('SET', currentWindowKey, formatWindowValue(curStart, curCount))
    redis.call('EXPIRE', currentWindowKey, ttlSeconds)
    
    redis.call('SET', previousWindowKey, formatWindowValue(prevStart, prevCount))
    redis.call('EXPIRE', previousWindowKey, ttlSeconds)
    
    -- Calculate remaining requests
    local remaining = limit - (curCount + weightedPreviousCount)
    if remaining < 0 then
        remaining = 0
    end
    
    -- Return: [allowed=1, remaining, currentCount, previousCount, weight]
    return {1, remaining, curCount, prevCount, weight}
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
    return {0, remaining, curCount, prevCount, weight, retryAfterSeconds}
end