package com.tcs.bancs.RK;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: RKDGExposureGrabber
 * Specialized query and data retrieval component for RK domain entities.
 */
public class RKDGExposureGrabber {

    private final Map<String, VaRCalculationResult> entityCache = new ConcurrentHashMap<>();

    public RKDGExposureGrabber() {
    }

    public VaRCalculationResult fetchVaRCalculationResultById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            VaRCalculationResult entity = new VaRCalculationResult();
            entity.Get(k);
            return entity;
        });
    }

    public List<VaRCalculationResult> retrieveAll() {
        List<VaRCalculationResult> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            VaRCalculationResult sample = new VaRCalculationResult();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<VaRCalculationResult> retrieveActiveVaRCalculationResults() {
        return retrieveAll();
    }

    public MO_StressTestScenario grabVaRCalculationResultSummary(String id) {
        VaRCalculationResult entity = fetchVaRCalculationResultById(id);
        MO_StressTestScenario summary = new MO_StressTestScenario();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "RKDGExposureGrabber", id, "grabSummary");
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
