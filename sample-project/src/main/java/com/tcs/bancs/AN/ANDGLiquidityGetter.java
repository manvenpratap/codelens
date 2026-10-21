package com.tcs.bancs.AN;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: ANDGLiquidityGetter
 * Archetype: Cursor Data Getter
 * Specialized query and data retrieval component for AN domain entities.
 */
public class ANDGLiquidityGetter {

    private final Map<String, LiquidityMetrics> entityCache = new ConcurrentHashMap<>();

    public ANDGLiquidityGetter() {
    }

    /**
     * Type 3: Cursor Data Getter - Streaming cursor lifecycle (Open, Fetch, Close).
     */
    private Iterator<LiquidityMetrics> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "ANDGLiquidityGetter", criteria, "OPEN");
    }

    public LiquidityMetrics Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "ANDGLiquidityGetter", "STATUS", "CLOSED");
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
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "ANDGLiquidityGetter", id, "grabSummary");
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
