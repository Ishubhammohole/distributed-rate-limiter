import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const SCENARIO = __ENV.SCENARIO || 'hot_key';
const COMMON_LOAD = {
  executor: 'constant-arrival-rate',
  rate: Number(__ENV.RATE || 5000),
  timeUnit: '1s',
  duration: __ENV.DURATION || '60s',
  preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 200),
  maxVUs: Number(__ENV.MAX_VUS || 1000),
};

const UNIQUE_KEY_COUNT = 1000;

export const rate_limiter_allowed_rate = new Rate('rate_limiter_allowed_rate');
export const rate_limiter_blocked_rate = new Rate('rate_limiter_blocked_rate');
export const rate_limiter_total_latency_ms = new Trend('rate_limiter_total_latency_ms');
export const rate_limiter_api_processing_ms = new Trend('rate_limiter_api_processing_ms');
export const rate_limiter_redis_latency_ms = new Trend('rate_limiter_redis_latency_ms');
export const rate_limiter_serialization_latency_ms = new Trend('rate_limiter_serialization_latency_ms');

export const options = {
  scenarios: {
    [SCENARIO]: COMMON_LOAD,
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'],
};

function keyForIndex(index) {
  return `benchmark:key:${index % UNIQUE_KEY_COUNT}`;
}

function hotKeyRequest() {
  return {
    key: 'benchmark:hot:key',
    algorithm: 'token_bucket',
    limit: 1000,
    window: '1s',
    cost: 1,
  };
}

function uniqueKeyRequest() {
  const iteration = exec.scenario.iterationInTest;
  return {
    key: keyForIndex(iteration),
    algorithm: 'token_bucket',
    limit: 10,
    window: '1s',
    cost: 1,
  };
}

function mixedTrafficRequest() {
  const progress = exec.scenario.progress;
  const iteration = exec.scenario.iterationInTest;
  const inBurstWindow = progress >= 0.3333 && progress < 0.5833;
  const hotTrafficPercent = inBurstWindow ? 70 : 20;
  const selector = iteration % 100;

  if (selector < hotTrafficPercent) {
    return hotKeyRequest();
  }

  return {
    key: keyForIndex(iteration),
    algorithm: 'token_bucket',
    limit: 10,
    window: '1s',
    cost: 1,
  };
}

function buildRequest() {
  switch (SCENARIO) {
    case 'hot_key':
      return hotKeyRequest();
    case 'unique_keys_1k':
      return uniqueKeyRequest();
    case 'mixed_traffic':
      return mixedTrafficRequest();
    default:
      throw new Error(`Unsupported scenario: ${SCENARIO}`);
  }
}

export default function () {
  const payload = JSON.stringify(buildRequest());
  const response = http.post(`${BASE_URL}/api/v1/ratelimit/check`, payload, {
    headers: {
      'Content-Type': 'application/json',
    },
    timeout: '5s',
    tags: {
      benchmark_scenario: SCENARIO,
    },
  });

  let body = null;
  try {
    body = response.json();
  } catch (error) {
    body = null;
  }

  if (__ENV.DEBUG_HEADERS === '1' && exec.scenario.iterationInTest === 0) {
    console.log(`response_headers=${JSON.stringify(response.headers)}`);
  }

  const allowed = response.status === 200 && body !== null && typeof body.allowed === 'boolean' && body.allowed;
  const blocked = response.status === 200 && body !== null && typeof body.allowed === 'boolean' && !body.allowed;

  recordTimingHeader(response, 'X-RateLimiter-Total-Ms', rate_limiter_total_latency_ms);
  recordTimingHeader(response, 'X-RateLimiter-Api-Ms', rate_limiter_api_processing_ms);
  recordTimingHeader(response, 'X-RateLimiter-Redis-Ms', rate_limiter_redis_latency_ms);
  recordTimingHeader(response, 'X-RateLimiter-Serialization-Ms', rate_limiter_serialization_latency_ms);

  rate_limiter_allowed_rate.add(allowed);
  rate_limiter_blocked_rate.add(blocked);

  check(response, {
    'status is 200': (r) => r.status === 200,
    'response contains allowed flag': () => body !== null && typeof body.allowed === 'boolean',
    'response contains remaining count': () => body !== null && typeof body.remaining === 'number',
    'response contains reset time': () => body !== null && typeof body.resetTime === 'string',
    'allowed responses omit retryAfter': () =>
      body !== null && (body.allowed ? body.retryAfter === null || body.retryAfter === undefined : true),
    'blocked responses include retryAfter': () =>
      body !== null && (!body.allowed ? typeof body.retryAfter === 'string' && body.retryAfter.length > 0 : true),
  });
}

function recordTimingHeader(response, headerName, metric) {
  const targetHeader = headerName.toLowerCase();
  let headerValue = response.headers[headerName] ?? response.headers[headerName.toLowerCase()];
  if (!headerValue) {
    for (const [responseHeader, value] of Object.entries(response.headers)) {
      if (responseHeader.toLowerCase() === targetHeader) {
        headerValue = value;
        break;
      }
    }
  }
  if (!headerValue) {
    return;
  }

  const parsed = Number.parseFloat(Array.isArray(headerValue) ? headerValue[0] : headerValue);
  if (Number.isFinite(parsed)) {
    metric.add(parsed);
  }
}
