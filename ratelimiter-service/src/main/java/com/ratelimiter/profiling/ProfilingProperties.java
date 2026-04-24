package com.ratelimiter.profiling;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate-limiter.profiling")
public class ProfilingProperties {

    private boolean enabled;
    private boolean logPerRequest;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLogPerRequest() {
        return logPerRequest;
    }

    public void setLogPerRequest(boolean logPerRequest) {
        this.logPerRequest = logPerRequest;
    }
}
