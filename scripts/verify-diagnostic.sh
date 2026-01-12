#!/bin/bash

# Sliding Window Counter - Diagnostic Test
# This test shows the exact Redis state and calculations during the semantic truth test

set -e

API_URL="http://localhost:8080/api/v1/ratelimit/check"
REDIS_HOST="localhost"
REDIS_PORT="6379"
TEST_KEY="diagnostic-test-$(date +%s)"
LIMIT=3
WINDOW="10s"
WINDOW_MS=10000

echo "=== SLIDING WINDOW COUNTER DIAGNOSTIC TEST ==="
echo "Test Key: $TEST_KEY"
echo "Limit: $LIMIT requests per $WINDOW"
echo ""

# Function to show Redis state and calculations
show_diagnostic() {
    local label="$1"
    local now_ms=$(date +%s%3N)
    
    echo "=== $label ==="
    echo "Time: ${now_ms}ms ($(date '+%H:%M:%S.%3N'))"
    
    # Calculate window info
    local current_window=$((now_ms / WINDOW_MS))
    local window_start=$((current_window * WINDOW_MS))
    local time_into_window=$((now_ms - window_start))
    local weight=$(echo "scale=6; ($WINDOW_MS - $time_into_window) / $WINDOW_MS" | bc -l)
    
    echo "Current window: $current_window"
    echo "Window start: ${window_start}ms"
    echo "Time into window: ${time_into_window}ms"
    echo "Previous window weight: $weight"
    
    # Get Redis state
    local current_key="rate_limit:sliding_window_counter:$TEST_KEY:$current_window"
    local previous_key="rate_limit:sliding_window_counter:$TEST_KEY:$((current_window - 1))"
    
    local current_count=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" GET "$current_key" 2>/dev/null)
    local previous_count=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" GET "$previous_key" 2>/dev/null)
    
    # Handle null values
    if [ -z "$current_count" ] || [ "$current_count" = "(nil)" ]; then
        current_count=0
    fi
    if [ -z "$previous_count" ] || [ "$previous_count" = "(nil)" ]; then
        previous_count=0
    fi
    
    echo "Redis current key: $current_key = $current_count"
    echo "Redis previous key: $previous_key = $previous_count"
    
    # Calculate estimated count
    local weighted_previous=$(echo "scale=0; $previous_count * $weight / 1" | bc -l | cut -d. -f1)
    local estimated_count=$((current_count + weighted_previous))
    
    echo "Weighted previous count: floor($previous_count * $weight) = $weighted_previous"
    echo "Estimated total count: $current_count + $weighted_previous = $estimated_count"
    echo "Decision logic: $estimated_count + 1 <= $LIMIT ? $([ $((estimated_count + 1)) -le $LIMIT ] && echo "ALLOW" || echo "DENY")"
    echo ""
}

# Function to make request with diagnostics
make_request_with_diagnostics() {
    local label="$1"
    
    # Show state before request
    show_diagnostic "BEFORE $label"
    
    # Make request
    local response=$(curl -s -X POST "$API_URL" \
        -H "Content-Type: application/json" \
        -d "{\"key\": \"$TEST_KEY\", \"limit\": $LIMIT, \"window\": \"$WINDOW\", \"algorithm\": \"sliding_window_counter\"}")
    
    echo "API Response: $response"
    local allowed=$(echo "$response" | jq -r '.allowed')
    echo "Result: $allowed"
    
    # Show state after request
    show_diagnostic "AFTER $label"
    
    return 0
}

# Clean up
echo "=== CLEANUP ==="
redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" DEL $(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "rate_limit:sliding_window_counter:$TEST_KEY:*" 2>/dev/null || echo "") >/dev/null 2>&1 || true
echo "Cleaned up existing test keys"
echo ""

# Test the critical scenario: 3 requests near end of window
echo "=== PHASE 1: SEND 3 REQUESTS NEAR END OF WINDOW ==="

# Wait until we're at ~9.5s into the window
current_time_ms=$(date +%s%3N)
current_window=$((current_time_ms / WINDOW_MS))
window_start=$((current_window * WINDOW_MS))
target_time=$((window_start + 9500))  # 9.5s into window

echo "Waiting to reach 9.5s into current window..."
current_time_ms=$(date +%s%3N)
if [ "$current_time_ms" -lt "$target_time" ]; then
    wait_time_ms=$((target_time - current_time_ms))
    wait_time_s=$(echo "scale=1; $wait_time_ms / 1000" | bc -l)
    echo "Waiting ${wait_time_s}s..."
    sleep $wait_time_s
fi

# Send 3 requests quickly
make_request_with_diagnostics "REQUEST 1"
sleep 0.1
make_request_with_diagnostics "REQUEST 2"
sleep 0.1
make_request_with_diagnostics "REQUEST 3"

echo "=== PHASE 2: CROSS BOUNDARY AND TEST ==="

# Wait for boundary + small offset
current_time_ms=$(date +%s%3N)
current_window=$((current_time_ms / WINDOW_MS))
next_window_start=$(((current_window + 1) * WINDOW_MS))
target_time=$((next_window_start + 100))  # 0.1s into next window

wait_time_ms=$((target_time - current_time_ms))
wait_time_s=$(echo "scale=1; $wait_time_ms / 1000" | bc -l)
echo "Waiting ${wait_time_s}s to cross boundary..."
sleep $wait_time_s

make_request_with_diagnostics "REQUEST 4 (BOUNDARY TEST)"

echo "=== ANALYSIS ==="
echo "The 4th request should be DENIED because:"
echo "- Previous window has 3 requests"
echo "- Weight should be ~0.99 (very close to boundary)"
echo "- Weighted previous = floor(3 * 0.99) = 2"
echo "- Current window = 0 (before request)"
echo "- Estimated = 0 + 2 = 2"
echo "- Decision: 2 + 1 <= 3 ? Should be ALLOW"
echo ""
echo "Wait... this reveals the approximation error!"
echo "The sliding window counter approximation is not accurate enough"
echo "for requests very close to the boundary."