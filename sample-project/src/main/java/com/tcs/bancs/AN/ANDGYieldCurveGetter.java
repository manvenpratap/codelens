package com.tcs.bancs.AN;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: ANDGYieldCurveGetter
 * Archetype: Paging Data Getter
 * Specialized query and data retrieval component for AN domain entities.
 */
public class ANDGYieldCurveGetter {

    private final Map<String, YieldCurveSnapshot> entityCache = new ConcurrentHashMap<>();

    public ANDGYieldCurveGetter() {
    }

    /**
     * Type 2: Paging Data Getter - Forward and backward pagination (GetMFwd, GetMBwd).
     */
    public List<YieldCurveSnapshot> GetMFwd(String startKey, int pageSize) {
        List<YieldCurveSnapshot> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<YieldCurveSnapshot> GetMBwd(String startKey, int pageSize) {
        List<YieldCurveSnapshot> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public YieldCurveSnapshot fetchYieldCurveSnapshotById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            YieldCurveSnapshot entity = new YieldCurveSnapshot();
            entity.Get(k);
            return entity;
        });
    }

    public List<YieldCurveSnapshot> retrieveAll() {
        List<YieldCurveSnapshot> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            YieldCurveSnapshot sample = new YieldCurveSnapshot();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<YieldCurveSnapshot> retrieveActiveYieldCurveSnapshots() {
        return retrieveAll();
    }

    public MO_AttributionFactor grabYieldCurveSnapshotSummary(String id) {
        YieldCurveSnapshot entity = fetchYieldCurveSnapshotById(id);
        MO_AttributionFactor summary = new MO_AttributionFactor();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "ANDGYieldCurveGetter", id, "grabSummary");
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
