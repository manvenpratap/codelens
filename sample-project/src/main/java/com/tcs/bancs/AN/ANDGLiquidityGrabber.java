package com.tcs.bancs.AN;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: ANDGLiquidityGrabber
 * Specialized query and data retrieval component for AN domain entities.
 */
public class ANDGLiquidityGrabber {

    private final Map<String, LiquidityMetrics> entityCache = new ConcurrentHashMap<>();

    public ANDGLiquidityGrabber() {
    }

    public LiquidityMetrics fetchLiquidityMetricsById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            LiquidityMetrics entity = new LiquidityMetrics();
            entity.Get(k);
            return entity;
        });
    }

    public List<LiquidityMetrics> retrieveAll() {
        List<LiquidityMetrics> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            LiquidityMetrics sample = new LiquidityMetrics();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<LiquidityMetrics> retrieveActiveLiquidityMetricss() {
        return retrieveAll();
    }

    public MO_AttributionFactor grabLiquidityMetricsSummary(String id) {
        LiquidityMetrics entity = fetchLiquidityMetricsById(id);
        MO_AttributionFactor summary = new MO_AttributionFactor();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "ANDGLiquidityGrabber", id, "grabSummary");
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
