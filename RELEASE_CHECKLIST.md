# Release Checklist

## Pre-Release Testing

- [ ] Run unit tests: `./mvnw test`
- [ ] Run verification scripts: `make verify`
- [ ] Start infrastructure: `docker-compose -f infra/docker-compose.yml up -d`
- [ ] Test API endpoints:
  ```bash
  curl -X POST http://localhost:8080/api/v1/ratelimit/check \
    -H "Content-Type: application/json" \
    -d '{"key":"test:user","algorithm":"sliding_window_counter","limit":10,"window":"60s","cost":1}'
  ```

## Release Process

- [ ] Update version in `pom.xml`
- [ ] Create release tag: `git tag -a v1.0.0 -m "Release v1.0.0"`
- [ ] Push tag: `git push origin v1.0.0`
- [ ] Build Docker image: `docker build -t rate-limiter:v1.0.0 .`
- [ ] Push to registry (if applicable)

## Post-Release Verification

- [ ] Verify Grafana dashboards: http://localhost:3000
- [ ] Check Prometheus metrics: http://localhost:9090
- [ ] Validate health endpoint: http://localhost:8080/actuator/health
- [ ] Run load tests: `k6 run loadtest/k6/throughput-test.js`

## Rollback Plan

- [ ] Previous Docker image available
- [ ] Database migration rollback scripts (if applicable)
- [ ] Configuration rollback procedure documented