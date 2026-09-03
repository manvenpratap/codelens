package com.tcs.bancs.SC;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: SCDGValuationGrabber
 * Specialized query and data retrieval component for SC domain entities.
 */
public class SCDGValuationGrabber {

    private final Map<String, ValuationAppraisalReport> entityCache = new ConcurrentHashMap<>();

    public SCDGValuationGrabber() {
    }

    public ValuationAppraisalReport fetchValuationAppraisalReportById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            ValuationAppraisalReport entity = new ValuationAppraisalReport();
            entity.Get(k);
            return entity;
        });
    }

    public List<ValuationAppraisalReport> retrieveAll() {
        List<ValuationAppraisalReport> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            ValuationAppraisalReport sample = new ValuationAppraisalReport();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<ValuationAppraisalReport> retrieveActiveValuationAppraisalReports() {
        return retrieveAll();
    }

    public MO_CollateralReleaseRequest grabValuationAppraisalReportSummary(String id) {
        ValuationAppraisalReport entity = fetchValuationAppraisalReportById(id);
        MO_CollateralReleaseRequest summary = new MO_CollateralReleaseRequest();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "SCDGValuationGrabber", id, "grabSummary");
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
