package com.tcs.bancs.AN;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: ANDGAnalyticsGrabber
 * Specialized query and data retrieval component for AN domain entities.
 */
public class ANDGAnalyticsGrabber {

    private final Map<String, PnLSummaryRecord> entityCache = new ConcurrentHashMap<>();

    public ANDGAnalyticsGrabber() {
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
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "ANDGAnalyticsGrabber", id, "grabSummary");
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
