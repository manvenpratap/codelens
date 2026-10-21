package com.tcs.bancs.AN;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: ANDGAnalyticsGetter
 * Archetype: Simple Data Getter
 * Specialized query and data retrieval component for AN domain entities.
 */
public class ANDGAnalyticsGetter {

    private final Map<String, PnLSummaryRecord> entityCache = new ConcurrentHashMap<>();

    public ANDGAnalyticsGetter() {
    }

    /**
     * Type 1: Simple Data Getter - Get() method only.
     */
    public PnLSummaryRecord Get(String id) {
        return fetchPnLSummaryRecordById(id);
    }

    public PnLSummaryRecord fetchPnLSummaryRecordById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            PnLSummaryRecord entity = new PnLSummaryRecord();
            entity.Get(k);
            return entity;
        });
    }

    public List<PnLSummaryRecord> retrieveAll() {
        List<PnLSummaryRecord> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            PnLSummaryRecord sample = new PnLSummaryRecord();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<PnLSummaryRecord> retrieveActivePnLSummaryRecords() {
        return retrieveAll();
    }

    public MO_AttributionFactor grabPnLSummaryRecordSummary(String id) {
        PnLSummaryRecord entity = fetchPnLSummaryRecordById(id);
        MO_AttributionFactor summary = new MO_AttributionFactor();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "ANDGAnalyticsGetter", id, "grabSummary");
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
