package com.tcs.bancs.SC;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: SCDGPledgeGrabber
 * Specialized query and data retrieval component for SC domain entities.
 */
public class SCDGPledgeGrabber {

    private final Map<String, CollateralPledge> entityCache = new ConcurrentHashMap<>();

    public SCDGPledgeGrabber() {
    }

    public CollateralPledge fetchCollateralPledgeById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            CollateralPledge entity = new CollateralPledge();
            entity.Get(k);
            return entity;
        });
    }

    public List<CollateralPledge> retrieveAll() {
        List<CollateralPledge> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            CollateralPledge sample = new CollateralPledge();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<CollateralPledge> retrieveActiveCollateralPledges() {
        return retrieveAll();
    }

    public MO_CollateralReleaseRequest grabCollateralPledgeSummary(String id) {
        CollateralPledge entity = fetchCollateralPledgeById(id);
        MO_CollateralReleaseRequest summary = new MO_CollateralReleaseRequest();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "SCDGPledgeGrabber", id, "grabSummary");
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
