package com.tcs.bancs.CL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: CLDGNettingGrabber
 * Specialized query and data retrieval component for CL domain entities.
 */
public class CLDGNettingGrabber {

    private final Map<String, NettingBatch> entityCache = new ConcurrentHashMap<>();

    public CLDGNettingGrabber() {
    }

    public NettingBatch fetchNettingBatchById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            NettingBatch entity = new NettingBatch();
            entity.Get(k);
            return entity;
        });
    }

    public List<NettingBatch> retrieveAll() {
        List<NettingBatch> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            NettingBatch sample = new NettingBatch();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<NettingBatch> retrieveActiveNettingBatchs() {
        return retrieveAll();
    }

    public MO_FailReport grabNettingBatchSummary(String id) {
        NettingBatch entity = fetchNettingBatchById(id);
        MO_FailReport summary = new MO_FailReport();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "CLDGNettingGrabber", id, "grabSummary");
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
