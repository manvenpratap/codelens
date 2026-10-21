package com.tcs.bancs.CL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: CLDGFailGetter
 * Archetype: Paging Cursor Data Getter
 * Specialized query and data retrieval component for CL domain entities.
 */
public class CLDGFailGetter {

    private final Map<String, SettlementFailRecord> entityCache = new ConcurrentHashMap<>();

    public CLDGFailGetter() {
    }

    /**
     * Type 4: Paging Cursor Data Getter - Cursor lifecycle and paging (Open, Fetch, Close, GetMFwd, GetMBwd).
     */
    private Iterator<SettlementFailRecord> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "CLDGFailGetter", criteria, "OPEN");
    }

    public SettlementFailRecord Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "CLDGFailGetter", "STATUS", "CLOSED");
    }

    public List<SettlementFailRecord> GetMFwd(String startKey, int pageSize) {
        List<SettlementFailRecord> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<SettlementFailRecord> GetMBwd(String startKey, int pageSize) {
        List<SettlementFailRecord> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public SettlementFailRecord fetchSettlementFailRecordById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            SettlementFailRecord entity = new SettlementFailRecord();
            entity.Get(k);
            return entity;
        });
    }

    public List<SettlementFailRecord> retrieveAll() {
        List<SettlementFailRecord> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            SettlementFailRecord sample = new SettlementFailRecord();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<SettlementFailRecord> retrieveActiveSettlementFailRecords() {
        return retrieveAll();
    }

    public MO_FailReport grabSettlementFailRecordSummary(String id) {
        SettlementFailRecord entity = fetchSettlementFailRecordById(id);
        MO_FailReport summary = new MO_FailReport();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "CLDGFailGetter", id, "grabSummary");
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
