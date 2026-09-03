package com.tcs.bancs.SC;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: SCDGMarginCallGrabber
 * Specialized query and data retrieval component for SC domain entities.
 */
public class SCDGMarginCallGrabber {

    private final Map<String, MarginCallEvent> entityCache = new ConcurrentHashMap<>();

    public SCDGMarginCallGrabber() {
    }

    public MarginCallEvent fetchMarginCallEventById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            MarginCallEvent entity = new MarginCallEvent();
            entity.Get(k);
            return entity;
        });
    }

    public List<MarginCallEvent> retrieveAll() {
        List<MarginCallEvent> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            MarginCallEvent sample = new MarginCallEvent();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<MarginCallEvent> retrieveActiveMarginCallEvents() {
        return retrieveAll();
    }

    public MO_CollateralReleaseRequest grabMarginCallEventSummary(String id) {
        MarginCallEvent entity = fetchMarginCallEventById(id);
        MO_CollateralReleaseRequest summary = new MO_CollateralReleaseRequest();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "SCDGMarginCallGrabber", id, "grabSummary");
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
