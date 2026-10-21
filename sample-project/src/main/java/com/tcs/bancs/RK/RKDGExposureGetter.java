package com.tcs.bancs.RK;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: RKDGExposureGetter
 * Archetype: Paging Cursor Data Getter
 * Specialized query and data retrieval component for RK domain entities.
 */
public class RKDGExposureGetter {

    private final Map<String, VaRCalculationResult> entityCache = new ConcurrentHashMap<>();

    public RKDGExposureGetter() {
    }

    /**
     * Type 4: Paging Cursor Data Getter - Cursor lifecycle and paging (Open, Fetch, Close, GetMFwd, GetMBwd).
     */
    private Iterator<VaRCalculationResult> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "RKDGExposureGetter", criteria, "OPEN");
    }

    public VaRCalculationResult Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "RKDGExposureGetter", "STATUS", "CLOSED");
    }

    public List<VaRCalculationResult> GetMFwd(String startKey, int pageSize) {
        List<VaRCalculationResult> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<VaRCalculationResult> GetMBwd(String startKey, int pageSize) {
        List<VaRCalculationResult> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
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
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "RKDGExposureGetter", id, "grabSummary");
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
