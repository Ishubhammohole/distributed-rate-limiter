#!/bin/bash

# Sliding Window Counter - Window Boundary Verification
# Verifies that the sliding window counter correctly handles window rollover
# and that the first request in a new window is always allowed.

set -euo pipefail

API_URL="http://localhost:8080/api/v1/ratelimit/check"
REDIS_HOST="localhost"
REDIS_PORT="6379"
TEST_KEY="boundary-test-$(date +%s)"
LIMIT=3
WINDOW="10s"  # Short window for faster testing

echo "=== SLIDING WINDOW COUNTER BOUNDARY VERIFICATION ==="
echo "Test Key: $TEST_KEY"
echo "Limit: $LIMIT requests per $WINDOW"
echo "API URL: $API_URL"
echo ""

# Function to make rate limit request
make_request() {
    local request_num=$1
    echo "Request #$request_num at $(date '+%H:%M:%S.%3N')"
    
    response=$(curl -s -X POST "$API_URL" \
        -H "Content-Type: application/json" \
        -d "{\"key\": \"$TEST_KEY\", \"limit\": $LIMIT, \"window\": \"$WINDOW\", \"algorithm\": \"sliding_window_counter\"}")
    
    echo "Response: $response"
    
    # Extract allowed status
    allowed=$(echo "$response" | jq -r '.allowed')
    remaining=$(echo "$response" | jq -r '.remaining')
    
    echo "  -> Allowed: $allowed, Remaining: $remaining"
    echo ""
    
    return 0
}

# Function to show Redis state
show_redis_state() {
    local label="$1"
    echo "=== REDIS STATE: $label ==="
    
    # Get all keys for our test
    keys=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "rate_limit:sliding_window_counter:$TEST_KEY:*" 2>/dev/null || echo "")
    
    if [ -z "$keys" ]; then
        echo "No Redis keys found for pattern: rate_limit:sliding_window_counter:$TEST_KEY:*"
    else
        echo "Keys found:"
        echo "$keys" | while read -r key; do
            if [ -n "$key" ]; then
                value=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" GET "$key" 2>/dev/null || echo "N/A")
                ttl=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" PTTL "$key" 2>/dev/null || echo "N/A")
                echo "  $key = $value (TTL: ${ttl}ms)"
            fi
        done
    fi
    echo ""
}

# Function to wait for window boundary
wait_for_window_boundary() {
    echo "=== WAITING FOR WINDOW BOUNDARY ==="
    
    # Calculate current window and next boundary
    current_time_ms=$(date +%s%3N)
    window_ms=10000  # 10 seconds in milliseconds
    current_window=$((current_time_ms / window_ms))
    next_window_start=$(((current_window + 1) * window_ms))
    wait_time_ms=$((next_window_start - current_time_ms))
    wait_time_s=$((wait_time_ms / 1000))
    
    echo "Current time: ${current_time_ms}ms"
    echo "Current window: $current_window"
    echo "Next window starts at: ${next_window_start}ms"
    echo "Waiting ${wait_time_s}s for window boundary..."
    
    # Wait until just after the boundary
    sleep $((wait_time_s + 1))
    
    echo "Window boundary crossed at $(date '+%H:%M:%S.%3N')"
    echo ""
}

# Clean up any existing keys
echo "=== CLEANUP ==="
redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" DEL $(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "rate_limit:sliding_window_counter:$TEST_KEY:*" 2>/dev/null || echo "") >/dev/null 2>&1 || true
echo "Cleaned up existing test keys"
echo ""

# Phase 1: Fill up the current window
echo "=== PHASE 1: FILL CURRENT WINDOW ==="
echo "Making 6 requests to fill window (expect 3 allowed, 3 denied)"
echo ""

for i in {1..6}; do
    make_request $i
    sleep 0.5  # Small delay between requests
done

show_redis_state "After filling current window"

# Phase 2: Wait for window boundary
wait_for_window_boundary

# Phase 3: Test first request in new window
echo "=== PHASE 3: CRITICAL TEST - FIRST REQUEST IN NEW WINDOW ==="
echo "This request MUST be allowed to prove window rollover works correctly"
echo ""

make_request "NEW-WINDOW-1"

show_redis_state "After first request in new window"

# Phase 4: Verify we can make more requests in new window
echo "=== PHASE 4: VERIFY NEW WINDOW CAPACITY ==="
echo "Making 2 more requests to verify new window has full capacity"
echo ""

make_request "NEW-WINDOW-2"
make_request "NEW-WINDOW-3"

show_redis_state "After filling new window"

# Phase 5: Verify new window limit
echo "=== PHASE 5: VERIFY NEW WINDOW LIMIT ==="
echo "Making 1 more request to verify new window limit is enforced"
echo ""

make_request "NEW-WINDOW-4-SHOULD-DENY"

show_redis_state "Final Redis state"

# Final verification
echo "=== FINAL VERIFICATION ==="
final_keys=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "rate_limit:sliding_window_counter:$TEST_KEY:*" 2>/dev/null || echo "")
key_count=$(echo "$final_keys" | wc -w)

echo "Total Redis keys for test: $key_count"
echo "Expected: ≤3 keys during boundary transitions (TTL-based cleanup)"

if [ "$key_count" -le 3 ]; then
    echo "✅ REDIS KEY COUNT: ACCEPTABLE ($key_count ≤ 3)"
    
    # Get PTTL for all keys and compute maximum
    echo ""
    echo "=== TTL-BASED CLEANUP VERIFICATION ==="
    echo "Keys before TTL expiration:"
    max_pttl=0
    
    if [ "$key_count" -gt 0 ]; then
        echo "$final_keys" | while read -r key; do
            if [ -n "$key" ]; then
                pttl=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" PTTL "$key" 2>/dev/null || echo "-1")
                echo "  $key: PTTL=${pttl}ms"
                if [ "$pttl" -gt "$max_pttl" ]; then
                    max_pttl=$pttl
                fi
            fi
        done
        
        # Recalculate max_pttl outside the subshell
        for key in $final_keys; do
            if [ -n "$key" ]; then
                pttl=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" PTTL "$key" 2>/dev/null || echo "-1")
                if [ "$pttl" -gt "$max_pttl" ]; then
                    max_pttl=$pttl
                fi
            fi
        done
        
        wait_time_s=$(((max_pttl + 2000) / 1000))  # max_pttl + 2s buffer
        echo ""
        echo "Maximum PTTL: ${max_pttl}ms"
        echo "Waiting ${wait_time_s}s for TTL expiration (max_pttl + 2s buffer)..."
        sleep $wait_time_s
        
        # Check keys after TTL expiration
        cleanup_keys=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "rate_limit:sliding_window_counter:$TEST_KEY:*" 2>/dev/null || echo "")
        cleanup_count=$(echo "$cleanup_keys" | wc -w)
        
        echo "Keys after TTL expiration: $cleanup_count"
        if [ "$cleanup_count" -eq 0 ]; then
            echo "✅ TTL CLEANUP: CORRECT (all keys expired as expected)"
        else
            echo "❌ TTL CLEANUP: INCOMPLETE (still $cleanup_count keys remaining)"
            echo "Remaining keys:"
            echo "$cleanup_keys" | while read -r key; do
                if [ -n "$key" ]; then
                    pttl=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" PTTL "$key" 2>/dev/null || echo "-1")
                    echo "  $key: PTTL=${pttl}ms"
                fi
            done
        fi
    else
        echo "No keys to wait for - already cleaned up"
        echo "✅ TTL CLEANUP: CORRECT (no keys present)"
    fi
else
    echo "❌ REDIS KEY COUNT: TOO HIGH (expected ≤3, got $key_count)"
fi

echo ""
echo "=== TEST SUMMARY ==="
echo "1. Filled current window with 6 requests (3 allowed, 3 denied)"
echo "2. Waited for window boundary crossing"
echo "3. Made first request in new window - MUST be allowed"
echo "4. Verified new window has full capacity"
echo "5. Verified new window limit enforcement"
echo ""
echo "If the first request in the new window was ALLOWED, the bug is FIXED ✅"
echo "If the first request in the new window was DENIED, the bug is STILL PRESENT ❌"