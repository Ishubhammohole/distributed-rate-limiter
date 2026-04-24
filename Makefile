.PHONY: help test verify run clean fmt docker-up docker-down benchmark benchmark-smoke benchmark-stable benchmark-stress

help: ## Show this help message
	@echo 'Usage: make [target]'
	@echo ''
	@echo 'Targets:'
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  %-15s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

test: ## Run all tests
	cd ratelimiter-service && ./mvnw test

test-integration: ## Run integration tests (requires Redis)
	cd ratelimiter-service && ./mvnw verify

verify: ## Run all verification scripts
	@echo "Running verification scripts..."
	./scripts/verify-window-boundary.sh
	./scripts/verify-boundary-math.sh
	./scripts/verify-semantic-truth.sh

run: ## Start the rate limiter service (requires Redis)
	cd ratelimiter-service && ./mvnw spring-boot:run

clean: ## Clean build artifacts
	cd ratelimiter-service && ./mvnw clean
	rm -f dump.rdb

fmt: ## Format code (if available)
	cd ratelimiter-service && ./mvnw spotless:apply || echo "Spotless not configured"

docker-up: ## Start Redis and monitoring stack
	docker-compose -f infra/docker-compose.yml up -d

docker-down: ## Stop Docker services
	docker-compose -f infra/docker-compose.yml down

build: ## Build the application
	cd ratelimiter-service && ./mvnw compile

package: ## Package the application
	cd ratelimiter-service && ./mvnw package -DskipTests

benchmark: ## Run real k6 benchmarks and store measured results
	$(MAKE) benchmark-stable
	$(MAKE) benchmark-stress
	cp benchmark/results/baseline_stable.json benchmark/results/real_benchmark.json

benchmark-stable: ## Run stable headline benchmark at 3000 req/s for 60s
	RATE=3000 DURATION=60s PREALLOCATED_VUS=200 MAX_VUS=1000 OUTPUT_JSON=benchmark/results/baseline_stable.json bash benchmark/run-real-benchmark.sh

benchmark-stress: ## Run stress benchmark at 5000 req/s for 60s
	RATE=5000 DURATION=60s PREALLOCATED_VUS=200 MAX_VUS=1000 OUTPUT_JSON=benchmark/results/stress_5000.json bash benchmark/run-real-benchmark.sh

benchmark-smoke: ## Run a lightweight regression smoke benchmark at 500 req/s for 20s
	RATE=500 DURATION=20s PREALLOCATED_VUS=50 MAX_VUS=200 OUTPUT_JSON=benchmark/results/benchmark_smoke.json bash benchmark/run-real-benchmark.sh
