package com.tcs.bancs.GL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: GLDGPeriodGetter
 * Archetype: Paging Cursor Data Getter
 * Specialized query and data retrieval component for GL domain entities.
 */
public class GLDGPeriodGetter {

    private final Map<String, FinancialPeriod> entityCache = new ConcurrentHashMap<>();

    public GLDGPeriodGetter() {
    }

    /**
     * Type 4: Paging Cursor Data Getter - Cursor lifecycle and paging (Open, Fetch, Close, GetMFwd, GetMBwd).
     */
    private Iterator<FinancialPeriod> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "GLDGPeriodGetter", criteria, "OPEN");
    }

    public FinancialPeriod Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "GLDGPeriodGetter", "STATUS", "CLOSED");
    }

    public List<FinancialPeriod> GetMFwd(String startKey, int pageSize) {
        List<FinancialPeriod> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<FinancialPeriod> GetMBwd(String startKey, int pageSize) {
        List<FinancialPeriod> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
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
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "GLDGPeriodGetter", id, "grabSummary");
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
