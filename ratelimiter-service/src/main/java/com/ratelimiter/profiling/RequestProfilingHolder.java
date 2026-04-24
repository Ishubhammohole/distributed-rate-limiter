package com.ratelimiter.profiling;

public final class RequestProfilingHolder {

    private static final ThreadLocal<RequestProfilingContext> CURRENT = new ThreadLocal<>();

    private RequestProfilingHolder() {
    }

    public static void set(RequestProfilingContext context) {
        CURRENT.set(context);
    }

    public static RequestProfilingContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void recordRedisNanos(long nanos) {
        RequestProfilingContext context = CURRENT.get();
        if (context != null) {
            context.addRedisNanos(nanos);
        }
    }
}
