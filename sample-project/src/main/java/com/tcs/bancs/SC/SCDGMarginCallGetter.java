package com.tcs.bancs.SC;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: SCDGMarginCallGetter
 * Archetype: Cursor Data Getter
 * Specialized query and data retrieval component for SC domain entities.
 */
public class SCDGMarginCallGetter {

    private final Map<String, MarginCallEvent> entityCache = new ConcurrentHashMap<>();

    public SCDGMarginCallGetter() {
    }

    /**
     * Type 3: Cursor Data Getter - Streaming cursor lifecycle (Open, Fetch, Close).
     */
    private Iterator<MarginCallEvent> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "SCDGMarginCallGetter", criteria, "OPEN");
    }

    public MarginCallEvent Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "SCDGMarginCallGetter", "STATUS", "CLOSED");
    }

    public MarginCallEvent fetchMarginCallEventById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            MarginCallEvent entity = new MarginCallEvent();
            entity.Get(k);
            return entity;
        });
    }

    public List<MarginCallEvent> retrieveAll() {
        List<MarginCallEvent> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            MarginCallEvent sample = new MarginCallEvent();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<MarginCallEvent> retrieveActiveMarginCallEvents() {
        return retrieveAll();
    }

    public MO_CollateralReleaseRequest grabMarginCallEventSummary(String id) {
        MarginCallEvent entity = fetchMarginCallEventById(id);
        MO_CollateralReleaseRequest summary = new MO_CollateralReleaseRequest();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "SCDGMarginCallGetter", id, "grabSummary");
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
