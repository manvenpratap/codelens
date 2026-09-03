package com.tcs.bancs.LN;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: LNDGLoanGrabber
 * Specialized query and data retrieval component for LN domain entities.
 */
public class LNDGLoanGrabber {

    private final Map<String, Loan> entityCache = new ConcurrentHashMap<>();

    public LNDGLoanGrabber() {
    }

    public Loan fetchLoanById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            Loan entity = new Loan();
            entity.Get(k);
            return entity;
        });
    }

    public List<Loan> retrieveAll() {
        List<Loan> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            Loan sample = new Loan();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<Loan> retrieveActiveLoans() {
        return retrieveAll();
    }

    public MO_LoanAccountSummary grabLoanSummary(String id) {
        Loan entity = fetchLoanById(id);
        MO_LoanAccountSummary summary = new MO_LoanAccountSummary();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "LNDGLoanGrabber", id, "grabSummary");
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
