package com.tcs.bancs.LN;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: LNDGDisbursementGetter
 * Archetype: Paging Cursor Data Getter
 * Specialized query and data retrieval component for LN domain entities.
 */
public class LNDGDisbursementGetter {

    private final Map<String, DelinquencyRecord> entityCache = new ConcurrentHashMap<>();

    public LNDGDisbursementGetter() {
    }

    /**
     * Type 4: Paging Cursor Data Getter - Cursor lifecycle and paging (Open, Fetch, Close, GetMFwd, GetMBwd).
     */
    private Iterator<DelinquencyRecord> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "LNDGDisbursementGetter", criteria, "OPEN");
    }

    public DelinquencyRecord Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "LNDGDisbursementGetter", "STATUS", "CLOSED");
    }

    public List<DelinquencyRecord> GetMFwd(String startKey, int pageSize) {
        List<DelinquencyRecord> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<DelinquencyRecord> GetMBwd(String startKey, int pageSize) {
        List<DelinquencyRecord> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
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
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "LNDGDisbursementGetter", id, "grabSummary");
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
