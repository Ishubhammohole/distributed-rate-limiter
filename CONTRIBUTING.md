# Contributing

## Development Setup

1. **Prerequisites**
   - Java 17+
   - Maven 3.6+
   - Docker (for Redis)
   - Redis CLI tools

2. **Local Development**
   ```bash
   # Start Redis
   make docker-up
   
   # Run tests
   make test
   
   # Start the service
   make run
   ```

## Testing

- **Unit Tests**: `make test` - No external dependencies
- **Integration Tests**: `make test-integration` - Requires Redis
- **Verification Scripts**: `make verify` - End-to-end validation

## Code Standards

- Follow existing Java conventions
- Add tests for new features
- Update documentation for API changes
- Run verification scripts before submitting

## Adding New Rate Limiting Algorithms

1. Implement `RateLimiterStrategy` interface
2. Add corresponding Lua script in `resources/lua/`
3. Write unit and integration tests
4. Add verification script in `/scripts`
5. Update documentation

## Pull Request Process

1. Create feature branch from `main`
2. Make changes with tests
3. Run `make test && make verify`
4. Update documentation if needed
5. Submit PR with clear description

## Performance Testing

Use the load testing tools in `/loadtest` to validate performance under load.
Target: 10,000+ RPS with <10ms p99 latency.