package com.tcs.bancs.DP;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: DPDGMaturityGrabber
 * Specialized query and data retrieval component for DP domain entities.
 */
public class DPDGMaturityGrabber {

    private final Map<String, RecurringDepositSchedule> entityCache = new ConcurrentHashMap<>();

    public DPDGMaturityGrabber() {
    }

    public RecurringDepositSchedule fetchRecurringDepositScheduleById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            RecurringDepositSchedule entity = new RecurringDepositSchedule();
            entity.Get(k);
            return entity;
        });
    }

    public List<RecurringDepositSchedule> retrieveAll() {
        List<RecurringDepositSchedule> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            RecurringDepositSchedule sample = new RecurringDepositSchedule();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<RecurringDepositSchedule> retrieveActiveRecurringDepositSchedules() {
        return retrieveAll();
    }

    public MO_TdsCertificate grabRecurringDepositScheduleSummary(String id) {
        RecurringDepositSchedule entity = fetchRecurringDepositScheduleById(id);
        MO_TdsCertificate summary = new MO_TdsCertificate();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "DPDGMaturityGrabber", id, "grabSummary");
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
