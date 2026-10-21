package com.tcs.bancs.CL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: CLDGNettingGetter
 * Archetype: Paging Data Getter
 * Specialized query and data retrieval component for CL domain entities.
 */
public class CLDGNettingGetter {

    private final Map<String, NettingBatch> entityCache = new ConcurrentHashMap<>();

    public CLDGNettingGetter() {
    }

    /**
     * Type 2: Paging Data Getter - Forward and backward pagination (GetMFwd, GetMBwd).
     */
    public List<NettingBatch> GetMFwd(String startKey, int pageSize) {
        List<NettingBatch> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<NettingBatch> GetMBwd(String startKey, int pageSize) {
        List<NettingBatch> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public NettingBatch fetchNettingBatchById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            NettingBatch entity = new NettingBatch();
            entity.Get(k);
            return entity;
        });
    }

    public List<NettingBatch> retrieveAll() {
        List<NettingBatch> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            NettingBatch sample = new NettingBatch();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<NettingBatch> retrieveActiveNettingBatchs() {
        return retrieveAll();
    }

    public MO_FailReport grabNettingBatchSummary(String id) {
        NettingBatch entity = fetchNettingBatchById(id);
        MO_FailReport summary = new MO_FailReport();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "CLDGNettingGetter", id, "grabSummary");
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
