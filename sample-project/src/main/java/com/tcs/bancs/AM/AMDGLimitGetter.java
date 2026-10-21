package com.tcs.bancs.AM;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: AMDGLimitGetter
 * Archetype: Cursor Data Getter
 * Specialized query and data retrieval component for AM domain entities.
 */
public class AMDGLimitGetter {

    private final Map<String, OverdraftFacility> entityCache = new ConcurrentHashMap<>();

    public AMDGLimitGetter() {
    }

    /**
     * Type 3: Cursor Data Getter - Streaming cursor lifecycle (Open, Fetch, Close).
     */
    private Iterator<OverdraftFacility> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "AMDGLimitGetter", criteria, "OPEN");
    }

    public OverdraftFacility Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "AMDGLimitGetter", "STATUS", "CLOSED");
    }

    public OverdraftFacility fetchOverdraftFacilityById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            OverdraftFacility entity = new OverdraftFacility();
            entity.Get(k);
            return entity;
        });
    }

    public List<OverdraftFacility> retrieveAll() {
        List<OverdraftFacility> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            OverdraftFacility sample = new OverdraftFacility();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<OverdraftFacility> retrieveActiveOverdraftFacilitys() {
        return retrieveAll();
    }

    public MO_AccountSummary grabOverdraftFacilitySummary(String id) {
        OverdraftFacility entity = fetchOverdraftFacilityById(id);
        MO_AccountSummary summary = new MO_AccountSummary();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "AMDGLimitGetter", id, "grabSummary");
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
