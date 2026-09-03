package com.tcs.bancs.LN;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: LNDGDisbursementGrabber
 * Specialized query and data retrieval component for LN domain entities.
 */
public class LNDGDisbursementGrabber {

    private final Map<String, DelinquencyRecord> entityCache = new ConcurrentHashMap<>();

    public LNDGDisbursementGrabber() {
    }

    public DelinquencyRecord fetchDelinquencyRecordById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            DelinquencyRecord entity = new DelinquencyRecord();
            entity.Get(k);
            return entity;
        });
    }

    public List<DelinquencyRecord> retrieveAll() {
        List<DelinquencyRecord> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            DelinquencyRecord sample = new DelinquencyRecord();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<DelinquencyRecord> retrieveActiveDelinquencyRecords() {
        return retrieveAll();
    }

    public MO_LoanAccountSummary grabDelinquencyRecordSummary(String id) {
        DelinquencyRecord entity = fetchDelinquencyRecordById(id);
        MO_LoanAccountSummary summary = new MO_LoanAccountSummary();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "LNDGDisbursementGrabber", id, "grabSummary");
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
