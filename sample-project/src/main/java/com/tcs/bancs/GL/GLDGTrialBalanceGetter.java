package com.tcs.bancs.GL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: GLDGTrialBalanceGetter
 * Archetype: Cursor Data Getter
 * Specialized query and data retrieval component for GL domain entities.
 */
public class GLDGTrialBalanceGetter {

    private final Map<String, JournalPostingLeg> entityCache = new ConcurrentHashMap<>();

    public GLDGTrialBalanceGetter() {
    }

    /**
     * Type 3: Cursor Data Getter - Streaming cursor lifecycle (Open, Fetch, Close).
     */
    private Iterator<JournalPostingLeg> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "GLDGTrialBalanceGetter", criteria, "OPEN");
    }

    public JournalPostingLeg Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "GLDGTrialBalanceGetter", "STATUS", "CLOSED");
    }

    public JournalPostingLeg fetchJournalPostingLegById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            JournalPostingLeg entity = new JournalPostingLeg();
            entity.Get(k);
            return entity;
        });
    }

    public List<JournalPostingLeg> retrieveAll() {
        List<JournalPostingLeg> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            JournalPostingLeg sample = new JournalPostingLeg();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<JournalPostingLeg> retrieveActiveJournalPostingLegs() {
        return retrieveAll();
    }

    public MO_IntercompanyClearingEntry grabJournalPostingLegSummary(String id) {
        JournalPostingLeg entity = fetchJournalPostingLegById(id);
        MO_IntercompanyClearingEntry summary = new MO_IntercompanyClearingEntry();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "GLDGTrialBalanceGetter", id, "grabSummary");
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
