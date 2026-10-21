package com.tcs.bancs.MS;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: MSDGPayloadGetter
 * Archetype: Cursor Data Getter
 * Specialized query and data retrieval component for MS domain entities.
 */
public class MSDGPayloadGetter {

    private final Map<String, OutboundDispatchQueue> entityCache = new ConcurrentHashMap<>();

    public MSDGPayloadGetter() {
    }

    /**
     * Type 3: Cursor Data Getter - Streaming cursor lifecycle (Open, Fetch, Close).
     */
    private Iterator<OutboundDispatchQueue> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "MSDGPayloadGetter", criteria, "OPEN");
    }

    public OutboundDispatchQueue Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "MSDGPayloadGetter", "STATUS", "CLOSED");
    }

    public OutboundDispatchQueue fetchOutboundDispatchQueueById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            OutboundDispatchQueue entity = new OutboundDispatchQueue();
            entity.Get(k);
            return entity;
        });
    }

    public List<OutboundDispatchQueue> retrieveAll() {
        List<OutboundDispatchQueue> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            OutboundDispatchQueue sample = new OutboundDispatchQueue();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<OutboundDispatchQueue> retrieveActiveOutboundDispatchQueues() {
        return retrieveAll();
    }

    public MO_DeadLetterNotice grabOutboundDispatchQueueSummary(String id) {
        OutboundDispatchQueue entity = fetchOutboundDispatchQueueById(id);
        MO_DeadLetterNotice summary = new MO_DeadLetterNotice();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "MSDGPayloadGetter", id, "grabSummary");
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
