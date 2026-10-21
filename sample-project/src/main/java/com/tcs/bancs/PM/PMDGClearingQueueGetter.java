package com.tcs.bancs.PM;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: PMDGClearingQueueGetter
 * Archetype: Paging Cursor Data Getter
 * Specialized query and data retrieval component for PM domain entities.
 */
public class PMDGClearingQueueGetter {

    private final Map<String, ClearingReturnRecord> entityCache = new ConcurrentHashMap<>();

    public PMDGClearingQueueGetter() {
    }

    /**
     * Type 4: Paging Cursor Data Getter - Cursor lifecycle and paging (Open, Fetch, Close, GetMFwd, GetMBwd).
     */
    private Iterator<ClearingReturnRecord> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "PMDGClearingQueueGetter", criteria, "OPEN");
    }

    public ClearingReturnRecord Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "PMDGClearingQueueGetter", "STATUS", "CLOSED");
    }

    public List<ClearingReturnRecord> GetMFwd(String startKey, int pageSize) {
        List<ClearingReturnRecord> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<ClearingReturnRecord> GetMBwd(String startKey, int pageSize) {
        List<ClearingReturnRecord> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public ClearingReturnRecord fetchClearingReturnRecordById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            ClearingReturnRecord entity = new ClearingReturnRecord();
            entity.Get(k);
            return entity;
        });
    }

    public List<ClearingReturnRecord> retrieveAll() {
        List<ClearingReturnRecord> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            ClearingReturnRecord sample = new ClearingReturnRecord();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<ClearingReturnRecord> retrieveActiveClearingReturnRecords() {
        return retrieveAll();
    }

    public MO_LiquidityReservation grabClearingReturnRecordSummary(String id) {
        ClearingReturnRecord entity = fetchClearingReturnRecordById(id);
        MO_LiquidityReservation summary = new MO_LiquidityReservation();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "PMDGClearingQueueGetter", id, "grabSummary");
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
