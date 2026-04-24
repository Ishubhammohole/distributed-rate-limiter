package com.ratelimiter.profiling;

public class RequestProfilingContext {

    private final long requestStartNanos;
    private long apiProcessingNanos;
    private long redisNanos;

    public RequestProfilingContext(long requestStartNanos) {
        this.requestStartNanos = requestStartNanos;
    }

    public long getRequestStartNanos() {
        return requestStartNanos;
    }

    public long getApiProcessingNanos() {
        return apiProcessingNanos;
    }

    public void setApiProcessingNanos(long apiProcessingNanos) {
        this.apiProcessingNanos = apiProcessingNanos;
    }

    public long getRedisNanos() {
        return redisNanos;
    }

    public void addRedisNanos(long redisNanos) {
        this.redisNanos += redisNanos;
    }
}
