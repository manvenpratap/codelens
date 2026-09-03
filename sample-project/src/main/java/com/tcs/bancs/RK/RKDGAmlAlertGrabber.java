package com.tcs.bancs.RK;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: RKDGAmlAlertGrabber
 * Specialized query and data retrieval component for RK domain entities.
 */
public class RKDGAmlAlertGrabber {

    private final Map<String, AmlAlertRecord> entityCache = new ConcurrentHashMap<>();

    public RKDGAmlAlertGrabber() {
    }

    public AmlAlertRecord fetchAmlAlertRecordById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            AmlAlertRecord entity = new AmlAlertRecord();
            entity.Get(k);
            return entity;
        });
    }

    public List<AmlAlertRecord> retrieveAll() {
        List<AmlAlertRecord> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            AmlAlertRecord sample = new AmlAlertRecord();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<AmlAlertRecord> retrieveActiveAmlAlertRecords() {
        return retrieveAll();
    }

    public MO_StressTestScenario grabAmlAlertRecordSummary(String id) {
        AmlAlertRecord entity = fetchAmlAlertRecordById(id);
        MO_StressTestScenario summary = new MO_StressTestScenario();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "RKDGAmlAlertGrabber", id, "grabSummary");
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
