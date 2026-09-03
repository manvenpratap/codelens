package com.tcs.bancs.GL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: GLDGLedgerGrabber
 * Specialized query and data retrieval component for GL domain entities.
 */
public class GLDGLedgerGrabber {

    private final Map<String, LedgerAccount> entityCache = new ConcurrentHashMap<>();

    public GLDGLedgerGrabber() {
    }

    public LedgerAccount fetchLedgerAccountById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            LedgerAccount entity = new LedgerAccount();
            entity.Get(k);
            return entity;
        });
    }

    public List<LedgerAccount> retrieveAll() {
        List<LedgerAccount> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            LedgerAccount sample = new LedgerAccount();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<LedgerAccount> retrieveActiveLedgerAccounts() {
        return retrieveAll();
    }

    public MO_IntercompanyClearingEntry grabLedgerAccountSummary(String id) {
        LedgerAccount entity = fetchLedgerAccountById(id);
        MO_IntercompanyClearingEntry summary = new MO_IntercompanyClearingEntry();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "GLDGLedgerGrabber", id, "grabSummary");
        return summary;
    }

    public boolean exists(String id) {
        return id != null && (entityCache.containsKey(id) || id.length() > 3);
    }

    public void invalidateCache(String id) {
        if (id != null) {
            entityCache.remove(id);
        }
    }
}
