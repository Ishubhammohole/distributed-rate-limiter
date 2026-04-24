#!/usr/bin/env bash
set -euo pipefail

BENCHMARK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${BENCHMARK_DIR}/.." && pwd)"
RESULTS_DIR="${BENCHMARK_DIR}/results"
COMPOSE_FILE="${REPO_DIR}/infra/docker-compose.yml"
RATE_LIMITER_PORT="${RATE_LIMITER_PORT:-18080}"
BASE_URL="${BASE_URL:-http://127.0.0.1:${RATE_LIMITER_PORT}}"
RATE="${RATE:-5000}"
DURATION="${DURATION:-60s}"
PREALLOCATED_VUS="${PREALLOCATED_VUS:-200}"
MAX_VUS="${MAX_VUS:-1000}"
OUTPUT_JSON="${OUTPUT_JSON:-${RESULTS_DIR}/real_benchmark.json}"
RESTART_BETWEEN_SCENARIOS="${RESTART_BETWEEN_SCENARIOS:-true}"
WARMUP_DURATION="${WARMUP_DURATION:-10s}"
SCENARIOS_CSV="${SCENARIOS_CSV:-hot_key,unique_keys_1k,mixed_traffic}"
IFS=',' read -r -a SCENARIOS <<< "${SCENARIOS_CSV}"

if [[ -z "${WARMUP_RATE:-}" ]]; then
  if (( RATE < 1000 )); then
    WARMUP_RATE="${RATE}"
  else
    WARMUP_RATE="1000"
  fi
else
  WARMUP_RATE="${WARMUP_RATE}"
fi

mkdir -p "${RESULTS_DIR}"

echo "Starting benchmark dependencies..."
RATE_LIMITER_PORT="${RATE_LIMITER_PORT}" docker compose -f "${COMPOSE_FILE}" up --build -d redis rate-limiter

echo "Waiting for rate limiter health endpoint..."
for _ in $(seq 1 90); do
  if curl -fsS "${BASE_URL}/actuator/health" >/dev/null; then
    break
  fi
  sleep 2
done

if ! curl -fsS "${BASE_URL}/actuator/health" >/dev/null; then
  echo "Rate limiter did not become healthy at ${BASE_URL}" >&2
  exit 1
fi

wait_for_health() {
  for _ in $(seq 1 90); do
    if curl -fsS "${BASE_URL}/actuator/health" >/dev/null; then
      return 0
    fi
    sleep 2
  done

  return 1
}

wait_for_redis() {
  for _ in $(seq 1 60); do
    if RATE_LIMITER_PORT="${RATE_LIMITER_PORT}" docker compose -f "${COMPOSE_FILE}" exec -T redis redis-cli ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  return 1
}

collect_scenario_artifacts() {
  local scenario="$1"
  docker stats --no-stream --format '{{ json . }}' rate-limiter-service rate-limiter-redis > "${RESULTS_DIR}/${scenario}-docker-stats.json" || true
  RATE_LIMITER_PORT="${RATE_LIMITER_PORT}" docker compose -f "${COMPOSE_FILE}" logs rate-limiter --tail=200 > "${RESULTS_DIR}/${scenario}-service.log" || true
  RATE_LIMITER_PORT="${RATE_LIMITER_PORT}" docker compose -f "${COMPOSE_FILE}" logs redis --tail=200 > "${RESULTS_DIR}/${scenario}-redis.log" || true
  RATE_LIMITER_PORT="${RATE_LIMITER_PORT}" docker compose -f "${COMPOSE_FILE}" exec -T redis redis-cli INFO stats > "${RESULTS_DIR}/${scenario}-redis-info-stats.txt" || true
  RATE_LIMITER_PORT="${RATE_LIMITER_PORT}" docker compose -f "${COMPOSE_FILE}" exec -T redis redis-cli INFO commandstats > "${RESULTS_DIR}/${scenario}-redis-info-commandstats.txt" || true
  RATE_LIMITER_PORT="${RATE_LIMITER_PORT}" docker compose -f "${COMPOSE_FILE}" exec -T redis redis-cli INFO memory > "${RESULTS_DIR}/${scenario}-redis-info-memory.txt" || true
  RATE_LIMITER_PORT="${RATE_LIMITER_PORT}" docker compose -f "${COMPOSE_FILE}" exec -T redis redis-cli DBSIZE > "${RESULTS_DIR}/${scenario}-redis-dbsize.txt" || true
}

flush_redis() {
  RATE_LIMITER_PORT="${RATE_LIMITER_PORT}" docker compose -f "${COMPOSE_FILE}" exec -T redis redis-cli FLUSHALL >/dev/null
}

python3 - "${RESULTS_DIR}/environment.json" "${BASE_URL}" "${RATE_LIMITER_PORT}" "${COMPOSE_FILE}" <<'PY'
import json
import platform
import subprocess
import sys
from datetime import datetime, timezone

output_path = sys.argv[1]

def read(command):
    completed = subprocess.run(command, capture_output=True, text=True, check=True)
    output = completed.stdout.strip()
    if output:
        return output
    return completed.stderr.strip()

def try_read(command):
    try:
        return read(command)
    except Exception:
        return None

env = {
    "measured_at": datetime.now(timezone.utc).isoformat(),
    "tooling": {
        "k6_version": try_read(["k6", "version"]),
        "docker_version": try_read(["docker", "--version"]),
        "docker_compose_version": try_read(["docker", "compose", "version"]),
        "java_version": try_read(["java", "-version"]),
    },
    "test_conditions": {
        "environment": "local machine with Docker Compose service + Redis",
        "service_runtime": "rate-limiter and redis running in Docker",
        "service_base_url": sys.argv[2],
        "service_host_port": sys.argv[3],
        "host_os": platform.platform(),
        "host_arch": platform.machine(),
        "cpu": try_read(["sysctl", "-n", "machdep.cpu.brand_string"]),
        "logical_cpu_count": try_read(["sysctl", "-n", "hw.ncpu"]),
        "memory_bytes": try_read(["sysctl", "-n", "hw.memsize"]),
        "docker_services": try_read(["docker", "compose", "-f", sys.argv[4], "ps", "--format", "json"]),
    },
}

with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(env, handle, indent=2)
    handle.write("\n")
PY

for scenario in "${SCENARIOS[@]}"; do
  echo
  if [[ "${RESTART_BETWEEN_SCENARIOS}" == "true" ]]; then
    echo "Restarting Redis and rate limiter before ${scenario} to isolate scenario state..."
    RATE_LIMITER_PORT="${RATE_LIMITER_PORT}" docker compose -f "${COMPOSE_FILE}" restart redis rate-limiter >/dev/null
    if ! wait_for_redis; then
      echo "Redis did not become healthy before scenario ${scenario}" >&2
      exit 1
    fi
    if ! wait_for_health; then
      echo "Rate limiter did not become healthy before scenario ${scenario}" >&2
      exit 1
    fi
  fi

  echo "Resetting Redis state for ${scenario}..."
  flush_redis

  echo "Warming up ${scenario} for ${WARMUP_DURATION} at ${WARMUP_RATE} req/s..."
  k6 run \
    -e BASE_URL="${BASE_URL}" \
    -e SCENARIO="${scenario}" \
    -e RATE="${WARMUP_RATE}" \
    -e DURATION="${WARMUP_DURATION}" \
    -e PREALLOCATED_VUS="${PREALLOCATED_VUS}" \
    -e MAX_VUS="${MAX_VUS}" \
    "${BENCHMARK_DIR}/k6/real-benchmark.js" > "${RESULTS_DIR}/${scenario}-warmup.log"

  echo "Resetting Redis state after warmup for ${scenario}..."
  flush_redis
  collect_scenario_artifacts "${scenario}-before"

  echo "Running k6 scenario: ${scenario}"
  k6 run \
    --summary-export "${RESULTS_DIR}/${scenario}-summary.json" \
    -e BASE_URL="${BASE_URL}" \
    -e SCENARIO="${scenario}" \
    -e RATE="${RATE}" \
    -e DURATION="${DURATION}" \
    -e PREALLOCATED_VUS="${PREALLOCATED_VUS}" \
    -e MAX_VUS="${MAX_VUS}" \
    "${BENCHMARK_DIR}/k6/real-benchmark.js" | tee "${RESULTS_DIR}/${scenario}.log"

  collect_scenario_artifacts "${scenario}-after"
done

python3 "${BENCHMARK_DIR}/scripts/generate_real_benchmark.py" \
  "${RESULTS_DIR}" \
  "${OUTPUT_JSON}" \
  "${RESULTS_DIR}/environment.json" \
  "${RATE}" \
  "${DURATION}" \
  "${PREALLOCATED_VUS}" \
  "${MAX_VUS}"

echo
echo "Benchmark complete. Results written to ${OUTPUT_JSON}"
