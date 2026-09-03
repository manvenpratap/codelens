package com.tcs.bancs.common;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Global audit logging framework for BaNCS domain transactions.
 */
public class AuditTrailService {
    private static final Queue<AuditEntryRecord> AUDIT_LOG = new ConcurrentLinkedQueue<>();

    public static void logAuditEvent(String category, String source, String correlationId, String detail) {
        AUDIT_LOG.offer(new AuditEntryRecord(category, source, correlationId, detail, System.currentTimeMillis()));
    }

    public static int getAuditLogSize() {
        return AUDIT_LOG.size();
    }

    public static void clear() {
        AUDIT_LOG.clear();
    }
}
