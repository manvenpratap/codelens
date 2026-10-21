package com.tcs.bancs.AN;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: ANDGReportGetter
 * Archetype: Paging Cursor Data Getter
 * Specialized query and data retrieval component for AN domain entities.
 */
public class ANDGReportGetter {

    private final Map<String, RegulatoryReportSnapshot> entityCache = new ConcurrentHashMap<>();

    public ANDGReportGetter() {
    }

    /**
     * Type 4: Paging Cursor Data Getter - Cursor lifecycle and paging (Open, Fetch, Close, GetMFwd, GetMBwd).
     */
    private Iterator<RegulatoryReportSnapshot> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "ANDGReportGetter", criteria, "OPEN");
    }

    public RegulatoryReportSnapshot Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "ANDGReportGetter", "STATUS", "CLOSED");
    }

    public List<RegulatoryReportSnapshot> GetMFwd(String startKey, int pageSize) {
        List<RegulatoryReportSnapshot> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<RegulatoryReportSnapshot> GetMBwd(String startKey, int pageSize) {
        List<RegulatoryReportSnapshot> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public RegulatoryReportSnapshot fetchRegulatoryReportSnapshotById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            RegulatoryReportSnapshot entity = new RegulatoryReportSnapshot();
            entity.Get(k);
            return entity;
        });
    }

    public List<RegulatoryReportSnapshot> retrieveAll() {
        List<RegulatoryReportSnapshot> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            RegulatoryReportSnapshot sample = new RegulatoryReportSnapshot();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<RegulatoryReportSnapshot> retrieveActiveRegulatoryReportSnapshots() {
        return retrieveAll();
    }

    public MO_AttributionFactor grabRegulatoryReportSnapshotSummary(String id) {
        RegulatoryReportSnapshot entity = fetchRegulatoryReportSnapshotById(id);
        MO_AttributionFactor summary = new MO_AttributionFactor();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "ANDGReportGetter", id, "grabSummary");
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
