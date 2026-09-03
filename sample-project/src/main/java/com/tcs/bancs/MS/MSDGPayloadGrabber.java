package com.tcs.bancs.MS;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: MSDGPayloadGrabber
 * Specialized query and data retrieval component for MS domain entities.
 */
public class MSDGPayloadGrabber {

    private final Map<String, OutboundDispatchQueue> entityCache = new ConcurrentHashMap<>();

    public MSDGPayloadGrabber() {
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
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "MSDGPayloadGrabber", id, "grabSummary");
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
