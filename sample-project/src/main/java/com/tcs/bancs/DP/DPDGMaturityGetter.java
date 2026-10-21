package com.tcs.bancs.DP;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: DPDGMaturityGetter
 * Archetype: Paging Cursor Data Getter
 * Specialized query and data retrieval component for DP domain entities.
 */
public class DPDGMaturityGetter {

    private final Map<String, RecurringDepositSchedule> entityCache = new ConcurrentHashMap<>();

    public DPDGMaturityGetter() {
    }

    /**
     * Type 4: Paging Cursor Data Getter - Cursor lifecycle and paging (Open, Fetch, Close, GetMFwd, GetMBwd).
     */
    private Iterator<RecurringDepositSchedule> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "DPDGMaturityGetter", criteria, "OPEN");
    }

    public RecurringDepositSchedule Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "DPDGMaturityGetter", "STATUS", "CLOSED");
    }

    public List<RecurringDepositSchedule> GetMFwd(String startKey, int pageSize) {
        List<RecurringDepositSchedule> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<RecurringDepositSchedule> GetMBwd(String startKey, int pageSize) {
        List<RecurringDepositSchedule> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
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
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "DPDGMaturityGetter", id, "grabSummary");
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
