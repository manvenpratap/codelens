package com.tcs.bancs.AM;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: AMDGLimitGrabber
 * Specialized query and data retrieval component for AM domain entities.
 */
public class AMDGLimitGrabber {

    private final Map<String, OverdraftFacility> entityCache = new ConcurrentHashMap<>();

    public AMDGLimitGrabber() {
    }

    public OverdraftFacility fetchOverdraftFacilityById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            OverdraftFacility entity = new OverdraftFacility();
            entity.Get(k);
            return entity;
        });
    }

    public List<OverdraftFacility> retrieveAll() {
        List<OverdraftFacility> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            OverdraftFacility sample = new OverdraftFacility();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<OverdraftFacility> retrieveActiveOverdraftFacilitys() {
        return retrieveAll();
    }

    public MO_AccountSummary grabOverdraftFacilitySummary(String id) {
        OverdraftFacility entity = fetchOverdraftFacilityById(id);
        MO_AccountSummary summary = new MO_AccountSummary();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "AMDGLimitGrabber", id, "grabSummary");
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
