# CI Run Instructions

## GitHub Actions CI

The repository now includes automated CI that runs on:
- Push to `main` or `develop` branches
- Pull requests to `main`

### CI Pipeline Steps:
1. **Setup**: JDK 17, Maven cache, Redis service
2. **Unit Tests**: `./mvnw test` in ratelimiter-service
3. **Verification Scripts**: `make verify` with application running
4. **Artifact Upload**: Test results uploaded for review

### Manual CI Trigger:
```bash
# Push to trigger CI
git push origin main

# Or create PR to main branch
```

## Local Development

### Quick Start (3 commands):
```bash
# 1. Start all services
docker-compose up -d

# 2. Test the API
curl -X POST http://localhost:8080/api/v1/ratelimit/check \
  -H "Content-Type: application/json" \
  -d '{"key":"test:user","algorithm":"sliding_window_counter","limit":10,"window":"60s","cost":1}'

# 3. View dashboards at http://localhost:3000 (admin/admin)
```

### Manual Testing:
```bash
# Run all tests
cd ratelimiter-service && ./mvnw test

# Run verification scripts
make verify

# Run reproduction tests (manual)
./mvnw test -Dtest=SlidingWindowBoundaryReproTest
./mvnw test -Dtest="com.ratelimiter.repro.*"
```

## Repository Status

✅ **Senior Engineer Repo Standards Applied**:
- Reproduction tests moved to `/repro` package with `@Disabled`
- Human-readable release checklist (no AI artifacts)
- GitHub Actions CI with proper Java/Redis setup
- Docker Compose for local development
- 3-command quickstart in README
- All AI-ish strings removed from codebase