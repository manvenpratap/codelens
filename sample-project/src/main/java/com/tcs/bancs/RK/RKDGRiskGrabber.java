package com.tcs.bancs.RK;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: RKDGRiskGrabber
 * Specialized query and data retrieval component for RK domain entities.
 */
public class RKDGRiskGrabber {

    private final Map<String, RiskExposure> entityCache = new ConcurrentHashMap<>();

    public RKDGRiskGrabber() {
    }

    public RiskExposure fetchRiskExposureById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            RiskExposure entity = new RiskExposure();
            entity.Get(k);
            return entity;
        });
    }

    public List<RiskExposure> retrieveAll() {
        List<RiskExposure> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            RiskExposure sample = new RiskExposure();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<RiskExposure> retrieveActiveRiskExposures() {
        return retrieveAll();
    }

    public MO_StressTestScenario grabRiskExposureSummary(String id) {
        RiskExposure entity = fetchRiskExposureById(id);
        MO_StressTestScenario summary = new MO_StressTestScenario();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "RKDGRiskGrabber", id, "grabSummary");
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
