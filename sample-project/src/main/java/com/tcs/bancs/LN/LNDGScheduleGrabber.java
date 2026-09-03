package com.tcs.bancs.LN;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: LNDGScheduleGrabber
 * Specialized query and data retrieval component for LN domain entities.
 */
public class LNDGScheduleGrabber {

    private final Map<String, LoanRepaymentSchedule> entityCache = new ConcurrentHashMap<>();

    public LNDGScheduleGrabber() {
    }

    public LoanRepaymentSchedule fetchLoanRepaymentScheduleById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            LoanRepaymentSchedule entity = new LoanRepaymentSchedule();
            entity.Get(k);
            return entity;
        });
    }

    public List<LoanRepaymentSchedule> retrieveAll() {
        List<LoanRepaymentSchedule> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            LoanRepaymentSchedule sample = new LoanRepaymentSchedule();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<LoanRepaymentSchedule> retrieveActiveLoanRepaymentSchedules() {
        return retrieveAll();
    }

    public MO_LoanAccountSummary grabLoanRepaymentScheduleSummary(String id) {
        LoanRepaymentSchedule entity = fetchLoanRepaymentScheduleById(id);
        MO_LoanAccountSummary summary = new MO_LoanAccountSummary();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "LNDGScheduleGrabber", id, "grabSummary");
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
