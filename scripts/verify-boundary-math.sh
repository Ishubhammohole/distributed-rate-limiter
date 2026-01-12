#!/bin/bash

# Sliding Window Counter - Mathematical Boundary Verification
# Verifies the exact mathematical calculations for remaining count
# immediately after window boundary crossing.

set -euo pipefail

API_URL="http://localhost:8080/api/v1/ratelimit/check"
REDIS_HOST="localhost"
REDIS_PORT="6379"
TEST_KEY="math-test-$(date +%s)"
LIMIT=3
WINDOW="10s"
WINDOW_MS=10000

echo "=== SLIDING WINDOW COUNTER BOUNDARY MATH VERIFICATION ==="
echo "Test Key: $TEST_KEY"
echo "Limit: $LIMIT requests per $WINDOW"
echo "Window Size: ${WINDOW_MS}ms"
echo ""

# Function to make request and extract detailed info
make_detailed_request() {
    local label="$1"
    local now_ms=$(date +%s%3N)
    
    echo "=== $label ==="
    echo "Request time: ${now_ms}ms ($(date '+%H:%M:%S.%3N'))"
    
    # Calculate window boundaries
    local current_window=$((now_ms / WINDOW_MS))
    local window_start=$((current_window * WINDOW_MS))
    local window_end=$(((current_window + 1) * WINDOW_MS))
    local time_into_window=$((now_ms - window_start))
    local weight=$(echo "scale=6; ($WINDOW_MS - $time_into_window) / $WINDOW_MS" | bc -l)
    
    echo "Current window: $current_window"
    echo "Window start: ${window_start}ms"
    echo "Window end: ${window_end}ms"
    echo "Time into window: ${time_into_window}ms"
    echo "Previous window weight: $weight"
    
    # Make API request
    response=$(curl -s -X POST "$API_URL" \
        -H "Content-Type: application/json" \
        -d "{\"key\": \"$TEST_KEY\", \"limit\": $LIMIT, \"window\": \"$WINDOW\", \"algorithm\": \"sliding_window_counter\"}")
    
    echo "API Response: $response"
    
    # Extract response fields
    allowed=$(echo "$response" | jq -r '.allowed')
    remaining=$(echo "$response" | jq -r '.remaining')
    
    # Get Redis state
    current_key="rate_limit:sliding_window_counter:$TEST_KEY:$current_window"
    previous_key="rate_limit:sliding_window_counter:$TEST_KEY:$((current_window - 1))"
    
    current_count=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" GET "$current_key" 2>/dev/null)
    previous_count=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" GET "$previous_key" 2>/dev/null)
    
    # Handle null/empty values from Redis
    if [ -z "$current_count" ] || [ "$current_count" = "(nil)" ]; then
        current_count=0
    fi
    if [ -z "$previous_count" ] || [ "$previous_count" = "(nil)" ]; then
        previous_count=0
    fi
    
    echo "Redis current key: $current_key = $current_count"
    echo "Redis previous key: $previous_key = $previous_count"
    
    # Calculate expected values (handle empty previous_count)
    if [ "$previous_count" = "0" ] || [ -z "$previous_count" ]; then
        weighted_previous=0
    else
        weighted_previous=$(echo "scale=0; $previous_count * $weight / 1" | bc -l | cut -d. -f1)
    fi
    estimated_count=$((current_count + weighted_previous))
    expected_remaining=$((LIMIT - estimated_count))
    
    echo "Weighted previous count: floor($previous_count * $weight) = $weighted_previous"
    echo "Estimated total count: $current_count + $weighted_previous = $estimated_count"
    echo "Expected remaining: $LIMIT - $estimated_count = $expected_remaining"
    echo "Actual remaining: $remaining"
    
    if [ "$remaining" -eq "$expected_remaining" ]; then
        echo "✅ REMAINING COUNT: CORRECT"
    else
        echo "❌ REMAINING COUNT: MISMATCH (expected $expected_remaining, got $remaining)"
    fi
    
    echo ""
    return 0
}

# Clean up any existing keys
echo "=== CLEANUP ==="
redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" DEL $(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "rate_limit:sliding_window_counter:$TEST_KEY:*" 2>/dev/null || echo "") >/dev/null 2>&1 || true
echo "Cleaned up existing test keys"
echo ""

# Phase 1: Fill up window near the end
echo "=== PHASE 1: FILL WINDOW NEAR END ==="
echo "Making 3 requests near end of window to set up test conditions"
echo ""

# Calculate when to make requests (near end of current window)
current_time_ms=$(date +%s%3N)
current_window=$((current_time_ms / WINDOW_MS))
window_end=$(((current_window + 1) * WINDOW_MS))
time_to_end=$((window_end - current_time_ms))

echo "Current time: ${current_time_ms}ms"
echo "Current window ends at: ${window_end}ms"
echo "Time to window end: ${time_to_end}ms"

# If we're too close to the end, wait for next window
if [ "$time_to_end" -lt 2000 ]; then
    echo "Too close to window end, waiting for next window..."
    sleep_time=$(((time_to_end + 1000) / 1000))
    sleep $sleep_time
    echo "Moved to next window"
    echo ""
fi

# Make 3 requests to fill the window
for i in {1..3}; do
    echo "Request #$i:"
    response=$(curl -s -X POST "$API_URL" \
        -H "Content-Type: application/json" \
        -d "{\"key\": \"$TEST_KEY\", \"limit\": $LIMIT, \"window\": \"$WINDOW\", \"algorithm\": \"sliding_window_counter\"}")
    echo "  Response: $response"
    sleep 0.2
done

echo ""

# Phase 2: Wait for boundary and make precise test
echo "=== PHASE 2: BOUNDARY CROSSING TEST ==="

# Calculate precise timing for boundary crossing
current_time_ms=$(date +%s%3N)
current_window=$((current_time_ms / WINDOW_MS))
next_window_start=$(((current_window + 1) * WINDOW_MS))
wait_time_ms=$((next_window_start - current_time_ms + 500))  # +500ms after boundary
wait_time_s=$(echo "scale=1; $wait_time_ms / 1000" | bc -l)

echo "Current time: ${current_time_ms}ms"
echo "Next window starts at: ${next_window_start}ms"
echo "Waiting ${wait_time_s}s to cross boundary (+500ms)"

sleep $wait_time_s

# Make the critical test request
make_detailed_request "FIRST REQUEST AFTER BOUNDARY"

# Phase 3: Verify math with second request
make_detailed_request "SECOND REQUEST IN NEW WINDOW"

echo "=== SUMMARY ==="
echo "This test verifies that the remaining count calculation is mathematically correct"
echo "based on the sliding window counter formula:"
echo "  estimatedCount = currentCount + floor(previousCount * weight)"
echo "  remaining = limit - estimatedCount"
echo "where weight = (windowSize - timeIntoCurrentWindow) / windowSize"