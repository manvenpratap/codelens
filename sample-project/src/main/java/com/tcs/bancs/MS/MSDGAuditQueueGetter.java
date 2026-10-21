package com.tcs.bancs.MS;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: MSDGAuditQueueGetter
 * Archetype: Paging Data Getter
 * Specialized query and data retrieval component for MS domain entities.
 */
public class MSDGAuditQueueGetter {

    private final Map<String, InboundPayloadStore> entityCache = new ConcurrentHashMap<>();

    public MSDGAuditQueueGetter() {
    }

    /**
     * Type 2: Paging Data Getter - Forward and backward pagination (GetMFwd, GetMBwd).
     */
    public List<InboundPayloadStore> GetMFwd(String startKey, int pageSize) {
        List<InboundPayloadStore> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<InboundPayloadStore> GetMBwd(String startKey, int pageSize) {
        List<InboundPayloadStore> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public InboundPayloadStore fetchInboundPayloadStoreById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            InboundPayloadStore entity = new InboundPayloadStore();
            entity.Get(k);
            return entity;
        });
    }

    public List<InboundPayloadStore> retrieveAll() {
        List<InboundPayloadStore> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            InboundPayloadStore sample = new InboundPayloadStore();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<InboundPayloadStore> retrieveActiveInboundPayloadStores() {
        return retrieveAll();
    }

    public MO_DeadLetterNotice grabInboundPayloadStoreSummary(String id) {
        InboundPayloadStore entity = fetchInboundPayloadStoreById(id);
        MO_DeadLetterNotice summary = new MO_DeadLetterNotice();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "MSDGAuditQueueGetter", id, "grabSummary");
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
