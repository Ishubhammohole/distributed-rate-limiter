.PHONY: help test verify run clean fmt docker-up docker-down benchmark

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
	bash benchmark/run-real-benchmark.sh
