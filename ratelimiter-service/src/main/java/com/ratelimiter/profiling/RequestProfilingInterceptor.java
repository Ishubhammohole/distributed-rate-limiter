package com.ratelimiter.profiling;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "rate-limiter.profiling", name = "enabled", havingValue = "true")
public class RequestProfilingInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RequestProfilingInterceptor.class);

    private final ProfilingProperties profilingProperties;
    private final MeterRegistry meterRegistry;

    public RequestProfilingInterceptor(ProfilingProperties profilingProperties, MeterRegistry meterRegistry) {
        this.profilingProperties = profilingProperties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!profilingProperties.isEnabled()) {
            return true;
        }

        RequestProfilingHolder.set(new RequestProfilingContext(System.nanoTime()));
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           @Nullable org.springframework.web.servlet.ModelAndView modelAndView) {
        if (!profilingProperties.isEnabled()) {
            return;
        }

        RequestProfilingContext context = RequestProfilingHolder.get();
        if (context != null) {
            context.setApiProcessingNanos(System.nanoTime() - context.getRequestStartNanos());
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                @Nullable Exception ex) {
        if (!profilingProperties.isEnabled()) {
            return;
        }

        RequestProfilingContext context = RequestProfilingHolder.get();
        if (context == null) {
            return;
        }
    }
}
