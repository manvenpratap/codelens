package com.tcs.bancs.PM;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: PMDGClearingQueueGrabber
 * Specialized query and data retrieval component for PM domain entities.
 */
public class PMDGClearingQueueGrabber {

    private final Map<String, ClearingReturnRecord> entityCache = new ConcurrentHashMap<>();

    public PMDGClearingQueueGrabber() {
    }

    public ClearingReturnRecord fetchClearingReturnRecordById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            ClearingReturnRecord entity = new ClearingReturnRecord();
            entity.Get(k);
            return entity;
        });
    }

    public List<ClearingReturnRecord> retrieveAll() {
        List<ClearingReturnRecord> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            ClearingReturnRecord sample = new ClearingReturnRecord();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<ClearingReturnRecord> retrieveActiveClearingReturnRecords() {
        return retrieveAll();
    }

    public MO_LiquidityReservation grabClearingReturnRecordSummary(String id) {
        ClearingReturnRecord entity = fetchClearingReturnRecordById(id);
        MO_LiquidityReservation summary = new MO_LiquidityReservation();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "PMDGClearingQueueGrabber", id, "grabSummary");
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
