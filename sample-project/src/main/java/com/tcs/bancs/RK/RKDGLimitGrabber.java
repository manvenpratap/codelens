package com.tcs.bancs.RK;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: RKDGLimitGrabber
 * Specialized query and data retrieval component for RK domain entities.
 */
public class RKDGLimitGrabber {

    private final Map<String, PartyRiskLimit> entityCache = new ConcurrentHashMap<>();

    public RKDGLimitGrabber() {
    }

    public PartyRiskLimit fetchPartyRiskLimitById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            PartyRiskLimit entity = new PartyRiskLimit();
            entity.Get(k);
            return entity;
        });
    }

    public List<PartyRiskLimit> retrieveAll() {
        List<PartyRiskLimit> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            PartyRiskLimit sample = new PartyRiskLimit();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<PartyRiskLimit> retrieveActivePartyRiskLimits() {
        return retrieveAll();
    }

    public MO_StressTestScenario grabPartyRiskLimitSummary(String id) {
        PartyRiskLimit entity = fetchPartyRiskLimitById(id);
        MO_StressTestScenario summary = new MO_StressTestScenario();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "RKDGLimitGrabber", id, "grabSummary");
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
