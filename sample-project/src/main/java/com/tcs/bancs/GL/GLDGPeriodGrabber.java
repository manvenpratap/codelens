package com.tcs.bancs.GL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: GLDGPeriodGrabber
 * Specialized query and data retrieval component for GL domain entities.
 */
public class GLDGPeriodGrabber {

    private final Map<String, FinancialPeriod> entityCache = new ConcurrentHashMap<>();

    public GLDGPeriodGrabber() {
    }

    public FinancialPeriod fetchFinancialPeriodById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            FinancialPeriod entity = new FinancialPeriod();
            entity.Get(k);
            return entity;
        });
    }

    public List<FinancialPeriod> retrieveAll() {
        List<FinancialPeriod> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            FinancialPeriod sample = new FinancialPeriod();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<FinancialPeriod> retrieveActiveFinancialPeriods() {
        return retrieveAll();
    }

    public MO_IntercompanyClearingEntry grabFinancialPeriodSummary(String id) {
        FinancialPeriod entity = fetchFinancialPeriodById(id);
        MO_IntercompanyClearingEntry summary = new MO_IntercompanyClearingEntry();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "GLDGPeriodGrabber", id, "grabSummary");
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
