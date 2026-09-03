package com.tcs.bancs.AM;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: AMDGStatementGrabber
 * Specialized query and data retrieval component for AM domain entities.
 */
public class AMDGStatementGrabber {

    private final Map<String, AccountFeeSchedule> entityCache = new ConcurrentHashMap<>();

    public AMDGStatementGrabber() {
    }

    public AccountFeeSchedule fetchAccountFeeScheduleById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            AccountFeeSchedule entity = new AccountFeeSchedule();
            entity.Get(k);
            return entity;
        });
    }

    public List<AccountFeeSchedule> retrieveAll() {
        List<AccountFeeSchedule> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            AccountFeeSchedule sample = new AccountFeeSchedule();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<AccountFeeSchedule> retrieveActiveAccountFeeSchedules() {
        return retrieveAll();
    }

    public MO_AccountSummary grabAccountFeeScheduleSummary(String id) {
        AccountFeeSchedule entity = fetchAccountFeeScheduleById(id);
        MO_AccountSummary summary = new MO_AccountSummary();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "AMDGStatementGrabber", id, "grabSummary");
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
