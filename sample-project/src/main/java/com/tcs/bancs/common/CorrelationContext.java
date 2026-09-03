package com.tcs.bancs.common;

import java.util.UUID;

public class CorrelationContext {
    private static final ThreadLocal<String> TRACE_ID = ThreadLocal.withInitial(() -> UUID.randomUUID().toString());

    public static String getTraceId() { return TRACE_ID.get(); }
    public static void setTraceId(String id) { TRACE_ID.set(id); }
    public static void reset() { TRACE_ID.set(UUID.randomUUID().toString()); }
}
