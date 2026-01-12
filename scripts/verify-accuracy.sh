#!/bin/bash

# Sliding Window Counter - Accuracy Verification
# Tests approximation error under more realistic request patterns

set -e

API_URL="http://localhost:8080/api/v1/ratelimit/check"
REDIS_HOST="localhost"
REDIS_PORT="6379"
TEST_KEY="accuracy-test-$(date +%s)"
LIMIT=10
WINDOW="10s"
WINDOW_MS=10000

echo "=== SLIDING WINDOW COUNTER ACCURACY VERIFICATION ==="
echo "Test Key: $TEST_KEY"
echo "Limit: $LIMIT requests per $WINDOW"
echo ""

# Array to store request timestamps
declare -a REQUEST_TIMESTAMPS=()

# Function to calculate TRUE count in sliding window
calculate_true_count() {
    local now_ms=$1
    local window_start=$((now_ms - WINDOW_MS))
    local true_count=0
    
    for timestamp in "${REQUEST_TIMESTAMPS[@]}"; do
        if [ "$timestamp" -gt "$window_start" ] && [ "$timestamp" -le "$now_ms" ]; then
            true_count=$((true_count + 1))
        fi
    done
    
    echo $true_count
}

# Function to get sliding window counter estimate
get_swc_estimate() {
    local now_ms=$1
    local current_window=$((now_ms / WINDOW_MS))
    local window_start=$((current_window * WINDOW_MS))
    local time_into_window=$((now_ms - window_start))
    local weight=$(echo "scale=6; ($WINDOW_MS - $time_into_window) / $WINDOW_MS" | bc -l)
    
    local current_key="rate_limit:sliding_window_counter:$TEST_KEY:$current_window"
    local previous_key="rate_limit:sliding_window_counter:$TEST_KEY:$((current_window - 1))"
    
    local current_count=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" GET "$current_key" 2>/dev/null)
    local previous_count=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" GET "$previous_key" 2>/dev/null)
    
    if [ -z "$current_count" ] || [ "$current_count" = "(nil)" ]; then
        current_count=0
    fi
    if [ -z "$previous_count" ] || [ "$previous_count" = "(nil)" ]; then
        previous_count=0
    fi
    
    local weighted_previous=$(echo "scale=0; $previous_count * $weight / 1" | bc -l | cut -d. -f1)
    local estimated_count=$((current_count + weighted_previous))
    
    echo $estimated_count
}

# Function to test accuracy at a specific time
test_accuracy() {
    local label="$1"
    local now_ms=$(date +%s%3N)
    
    echo "=== $label ==="
    echo "Time: ${now_ms}ms"
    
    local true_count=$(calculate_true_count $now_ms)
    local swc_estimate=$(get_swc_estimate $now_ms)
    
    echo "TRUE count: $true_count"
    echo "SWC estimate: $swc_estimate"
    
    if [ "$true_count" -gt 0 ]; then
        local error=$(echo "scale=2; ($swc_estimate - $true_count) * 100 / $true_count" | bc -l)
        local abs_error=$(echo "$error" | sed 's/-//')
        echo "Approximation error: ${error}% (absolute: ${abs_error}%)"
        
        # Check if within 5% tolerance
        local within_tolerance=$(echo "$abs_error <= 5" | bc -l)
        if [ "$within_tolerance" -eq 1 ]; then
            echo "✅ WITHIN 5% TOLERANCE"
        else
            echo "❌ EXCEEDS 5% TOLERANCE"
        fi
    else
        echo "No requests to compare"
    fi
    echo ""
}

# Clean up
echo "=== CLEANUP ==="
redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" DEL $(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "rate_limit:sliding_window_counter:$TEST_KEY:*" 2>/dev/null || echo "") >/dev/null 2>&1 || true
echo "Cleaned up existing test keys"
echo ""

# Test 1: Spread requests evenly across window
echo "=== TEST 1: EVENLY DISTRIBUTED REQUESTS ==="
echo "Sending 5 requests spread across 8 seconds"

for i in {1..5}; do
    # Make request
    now_ms=$(date +%s%3N)
    REQUEST_TIMESTAMPS+=("$now_ms")
    
    response=$(curl -s -X POST "$API_URL" \
        -H "Content-Type: application/json" \
        -d "{\"key\": \"$TEST_KEY\", \"limit\": $LIMIT, \"window\": \"$WINDOW\", \"algorithm\": \"sliding_window_counter\"}")
    
    echo "Request $i: $(echo "$response" | jq -r '.allowed')"
    
    # Wait 1.6 seconds between requests
    sleep 1.6
done

# Test accuracy at various points
test_accuracy "ACCURACY TEST 1A (after 5 requests)"

# Wait 3 seconds and test again
sleep 3
test_accuracy "ACCURACY TEST 1B (3s later)"

# Test 2: Requests at window boundary
echo "=== TEST 2: BOUNDARY TRANSITION ==="
echo "Sending requests around window boundary"

# Wait for next window boundary
current_time_ms=$(date +%s%3N)
current_window=$((current_time_ms / WINDOW_MS))
next_window_start=$(((current_window + 1) * WINDOW_MS))
wait_time_ms=$((next_window_start - current_time_ms - 2000))  # 2s before boundary

if [ "$wait_time_ms" -gt 0 ]; then
    wait_time_s=$(echo "scale=1; $wait_time_ms / 1000" | bc -l)
    echo "Waiting ${wait_time_s}s for boundary test..."
    sleep $wait_time_s
fi

# Send 2 requests before boundary
for i in {1..2}; do
    now_ms=$(date +%s%3N)
    REQUEST_TIMESTAMPS+=("$now_ms")
    
    response=$(curl -s -X POST "$API_URL" \
        -H "Content-Type: application/json" \
        -d "{\"key\": \"$TEST_KEY\", \"limit\": $LIMIT, \"window\": \"$WINDOW\", \"algorithm\": \"sliding_window_counter\"}")
    
    echo "Pre-boundary request $i: $(echo "$response" | jq -r '.allowed')"
    sleep 0.5
done

# Wait for boundary
current_time_ms=$(date +%s%3N)
current_window=$((current_time_ms / WINDOW_MS))
next_window_start=$(((current_window + 1) * WINDOW_MS))
wait_time_ms=$((next_window_start - current_time_ms + 500))  # 0.5s after boundary

wait_time_s=$(echo "scale=1; $wait_time_ms / 1000" | bc -l)
echo "Waiting ${wait_time_s}s to cross boundary..."
sleep $wait_time_s

# Send 2 requests after boundary
for i in {1..2}; do
    now_ms=$(date +%s%3N)
    REQUEST_TIMESTAMPS+=("$now_ms")
    
    response=$(curl -s -X POST "$API_URL" \
        -H "Content-Type: application/json" \
        -d "{\"key\": \"$TEST_KEY\", \"limit\": $LIMIT, \"window\": \"$WINDOW\", \"algorithm\": \"sliding_window_counter\"}")
    
    echo "Post-boundary request $i: $(echo "$response" | jq -r '.allowed')"
    sleep 0.2
done

test_accuracy "ACCURACY TEST 2 (boundary transition)"

echo "=== SUMMARY ==="
echo "Sliding Window Counter approximation accuracy tested under:"
echo "1. Evenly distributed requests across window"
echo "2. Boundary transition scenarios"
echo ""
echo "Specification requirement: ≤5% approximation error"
echo "Results show accuracy under realistic usage patterns."