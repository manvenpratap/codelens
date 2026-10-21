package com.tcs.bancs.CU;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: CUDGRelationshipGetter
 * Archetype: Cursor Data Getter
 * Specialized query and data retrieval component for CU domain entities.
 */
public class CUDGRelationshipGetter {

    private final Map<String, PartyRelationship> entityCache = new ConcurrentHashMap<>();

    public CUDGRelationshipGetter() {
    }

    /**
     * Type 3: Cursor Data Getter - Streaming cursor lifecycle (Open, Fetch, Close).
     */
    private Iterator<PartyRelationship> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "CUDGRelationshipGetter", criteria, "OPEN");
    }

    public PartyRelationship Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "CUDGRelationshipGetter", "STATUS", "CLOSED");
    }

    public PartyRelationship fetchPartyRelationshipById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            PartyRelationship entity = new PartyRelationship();
            entity.Get(k);
            return entity;
        });
    }

    public List<PartyRelationship> retrieveAll() {
        List<PartyRelationship> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            PartyRelationship sample = new PartyRelationship();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<PartyRelationship> retrieveActivePartyRelationships() {
        return retrieveAll();
    }

    public MO_CustomerCreditScore grabPartyRelationshipSummary(String id) {
        PartyRelationship entity = fetchPartyRelationshipById(id);
        MO_CustomerCreditScore summary = new MO_CustomerCreditScore();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "CUDGRelationshipGetter", id, "grabSummary");
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
