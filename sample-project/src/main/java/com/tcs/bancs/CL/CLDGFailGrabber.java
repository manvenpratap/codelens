package com.tcs.bancs.CL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: CLDGFailGrabber
 * Specialized query and data retrieval component for CL domain entities.
 */
public class CLDGFailGrabber {

    private final Map<String, SettlementFailRecord> entityCache = new ConcurrentHashMap<>();

    public CLDGFailGrabber() {
    }

    public SettlementFailRecord fetchSettlementFailRecordById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            SettlementFailRecord entity = new SettlementFailRecord();
            entity.Get(k);
            return entity;
        });
    }

    public List<SettlementFailRecord> retrieveAll() {
        List<SettlementFailRecord> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            SettlementFailRecord sample = new SettlementFailRecord();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<SettlementFailRecord> retrieveActiveSettlementFailRecords() {
        return retrieveAll();
    }

    public MO_FailReport grabSettlementFailRecordSummary(String id) {
        SettlementFailRecord entity = fetchSettlementFailRecordById(id);
        MO_FailReport summary = new MO_FailReport();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "CLDGFailGrabber", id, "grabSummary");
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
