package com.ratelimiter.profiling;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "rate-limiter.profiling", name = "enabled", havingValue = "true")
public class RequestProfilingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RequestProfilingFilter.class);

    private final ProfilingProperties profilingProperties;
    private final MeterRegistry meterRegistry;

    public RequestProfilingFilter(ProfilingProperties profilingProperties, MeterRegistry meterRegistry) {
        this.profilingProperties = profilingProperties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!profilingProperties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        RequestProfilingHolder.set(new RequestProfilingContext(System.nanoTime()));
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(request, responseWrapper);
        } finally {
            recordBreakdown(request, responseWrapper);
            responseWrapper.copyBodyToResponse();
            RequestProfilingHolder.clear();
        }
    }

    private void recordBreakdown(HttpServletRequest request, HttpServletResponse response) {
        RequestProfilingContext context = RequestProfilingHolder.get();
        if (context == null) {
            return;
        }

        long totalNanos = System.nanoTime() - context.getRequestStartNanos();
        long apiProcessingNanos = context.getApiProcessingNanos();
        if (apiProcessingNanos == 0L) {
            apiProcessingNanos = totalNanos;
        }
        long redisNanos = context.getRedisNanos();
        long serializationNanos = Math.max(0L, totalNanos - apiProcessingNanos);

        response.setHeader("X-RateLimiter-Total-Ms", formatMillis(totalNanos));
        response.setHeader("X-RateLimiter-Api-Ms", formatMillis(apiProcessingNanos));
        response.setHeader("X-RateLimiter-Redis-Ms", formatMillis(redisNanos));
        response.setHeader("X-RateLimiter-Serialization-Ms", formatMillis(serializationNanos));

        Timer.builder("rate_limiter.request.total")
                .tag("path", request.getRequestURI())
                .register(meterRegistry)
                .record(totalNanos, TimeUnit.NANOSECONDS);
        Timer.builder("rate_limiter.request.api")
                .tag("path", request.getRequestURI())
                .register(meterRegistry)
                .record(apiProcessingNanos, TimeUnit.NANOSECONDS);
        Timer.builder("rate_limiter.request.serialization")
                .tag("path", request.getRequestURI())
                .register(meterRegistry)
                .record(serializationNanos, TimeUnit.NANOSECONDS);

        if (profilingProperties.isLogPerRequest()) {
            logger.info(
                    "latency_breakdown path={} status={} total_ms={} api_ms={} redis_ms={} serialization_ms={}",
                    request.getRequestURI(),
                    response.getStatus(),
                    formatMillis(totalNanos),
                    formatMillis(apiProcessingNanos),
                    formatMillis(redisNanos),
                    formatMillis(serializationNanos)
            );
        }
    }

    private String formatMillis(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }
}
