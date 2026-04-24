#!/usr/bin/env python3

import json
import sys
from pathlib import Path


SCENARIO_ORDER = ["hot_key", "unique_keys_1k", "mixed_traffic"]


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def read_metric(summary, metric_name):
    metric = summary["metrics"].get(metric_name, {})
    if "values" in metric:
        return metric["values"]
    return metric


def read_rate_metric(values):
    for key in ("rate", "value"):
        if key in values:
            return values[key]
    return None


def scenario_result(summary, scenario_name):
    duration = read_metric(summary, "http_req_duration")
    failed = read_metric(summary, "http_req_failed")
    checks = read_metric(summary, "checks")
    http_reqs = read_metric(summary, "http_reqs")
    allowed = read_metric(summary, "rate_limiter_allowed_rate")
    blocked = read_metric(summary, "rate_limiter_blocked_rate")
    total_latency = read_metric(summary, "rate_limiter_total_latency_ms")
    api_latency = read_metric(summary, "rate_limiter_api_processing_ms")
    redis_latency = read_metric(summary, "rate_limiter_redis_latency_ms")
    serialization_latency = read_metric(summary, "rate_limiter_serialization_latency_ms")

    return {
        "scenario": scenario_name,
        "throughput_rps": read_rate_metric(http_reqs),
        "http_req_failed_rate": read_rate_metric(failed),
        "success_rate": read_rate_metric(checks),
        "rate_limiter_allowed_rate": read_rate_metric(allowed),
        "rate_limiter_blocked_rate": read_rate_metric(blocked),
        "latency_distribution_ms": {
            "avg": duration.get("avg"),
            "min": duration.get("min"),
            "p50": duration.get("p(50)", duration.get("med")),
            "p90": duration.get("p(90)"),
            "p95": duration.get("p(95)"),
            "p99": duration.get("p(99)"),
            "max": duration.get("max"),
        },
        "server_breakdown_ms": {
            "total": trend_values(total_latency),
            "api_processing": trend_values(api_latency),
            "redis": trend_values(redis_latency),
            "serialization": trend_values(serialization_latency),
        },
        "raw_summary_file": f"{scenario_name}-summary.json",
        "raw_stdout_log": f"{scenario_name}.log",
    }


def trend_values(metric):
    return {
        "avg": metric.get("avg"),
        "p50": metric.get("p(50)", metric.get("med")),
        "p95": metric.get("p(95)"),
        "p99": metric.get("p(99)"),
        "max": metric.get("max"),
    }


def main():
    if len(sys.argv) != 4:
        raise SystemExit(
            "Usage: generate_real_benchmark.py <results_dir> <output_json> <environment_json>"
        )

    results_dir = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    environment_path = Path(sys.argv[3])

    environment = load_json(environment_path)
    scenarios = []
    for scenario_name in SCENARIO_ORDER:
        summary = load_json(results_dir / f"{scenario_name}-summary.json")
        scenarios.append(scenario_result(summary, scenario_name))

    document = {
        "measured_at": environment.get("measured_at"),
        "tooling": environment.get("tooling"),
        "test_conditions": environment.get("test_conditions"),
        "load_profile": {
            "executor": "constant-arrival-rate",
            "rate": 5000,
            "timeUnit": "1s",
            "duration": "60s",
            "preAllocatedVUs": 200,
            "maxVUs": 1000,
        },
        "scenarios": scenarios,
    }

    with output_path.open("w", encoding="utf-8") as handle:
        json.dump(document, handle, indent=2)
        handle.write("\n")


if __name__ == "__main__":
    main()
