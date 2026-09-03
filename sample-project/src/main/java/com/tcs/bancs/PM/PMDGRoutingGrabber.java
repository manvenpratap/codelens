package com.tcs.bancs.PM;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: PMDGRoutingGrabber
 * Specialized query and data retrieval component for PM domain entities.
 */
public class PMDGRoutingGrabber {

    private final Map<String, RoutingDirectory> entityCache = new ConcurrentHashMap<>();

    public PMDGRoutingGrabber() {
    }

    public RoutingDirectory fetchRoutingDirectoryById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            RoutingDirectory entity = new RoutingDirectory();
            entity.Get(k);
            return entity;
        });
    }

    public List<RoutingDirectory> retrieveAll() {
        List<RoutingDirectory> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            RoutingDirectory sample = new RoutingDirectory();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<RoutingDirectory> retrieveActiveRoutingDirectorys() {
        return retrieveAll();
    }

    public MO_LiquidityReservation grabRoutingDirectorySummary(String id) {
        RoutingDirectory entity = fetchRoutingDirectoryById(id);
        MO_LiquidityReservation summary = new MO_LiquidityReservation();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "PMDGRoutingGrabber", id, "grabSummary");
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
