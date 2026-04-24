#!/usr/bin/env bash
set -euo pipefail

BENCHMARK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${BENCHMARK_DIR}/.." && pwd)"
RESULTS_DIR="${BENCHMARK_DIR}/results"
COMPOSE_FILE="${REPO_DIR}/infra/docker-compose.yml"
RATE_LIMITER_PORT="${RATE_LIMITER_PORT:-18080}"
BASE_URL="${BASE_URL:-http://127.0.0.1:${RATE_LIMITER_PORT}}"
SCENARIOS=("hot_key" "unique_keys_1k" "mixed_traffic")

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
  echo "Resetting Redis state for ${scenario}..."
  RATE_LIMITER_PORT="${RATE_LIMITER_PORT}" docker compose -f "${COMPOSE_FILE}" exec -T redis redis-cli FLUSHALL >/dev/null

  echo "Running k6 scenario: ${scenario}"
  k6 run \
    --summary-export "${RESULTS_DIR}/${scenario}-summary.json" \
    -e BASE_URL="${BASE_URL}" \
    -e SCENARIO="${scenario}" \
    "${BENCHMARK_DIR}/k6/real-benchmark.js" | tee "${RESULTS_DIR}/${scenario}.log"
done

python3 "${BENCHMARK_DIR}/scripts/generate_real_benchmark.py" \
  "${RESULTS_DIR}" \
  "${RESULTS_DIR}/real_benchmark.json" \
  "${RESULTS_DIR}/environment.json"

echo
echo "Benchmark complete. Results written to ${RESULTS_DIR}/real_benchmark.json"
