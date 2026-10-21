package com.tcs.bancs.MS;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: MSDGMessageGetter
 * Archetype: Simple Data Getter
 * Specialized query and data retrieval component for MS domain entities.
 */
public class MSDGMessageGetter {

    private final Map<String, MessageHeaderRecord> entityCache = new ConcurrentHashMap<>();

    public MSDGMessageGetter() {
    }

    /**
     * Type 1: Simple Data Getter - Get() method only.
     */
    public MessageHeaderRecord Get(String id) {
        return fetchMessageHeaderRecordById(id);
    }

    public MessageHeaderRecord fetchMessageHeaderRecordById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            MessageHeaderRecord entity = new MessageHeaderRecord();
            entity.Get(k);
            return entity;
        });
    }

    public List<MessageHeaderRecord> retrieveAll() {
        List<MessageHeaderRecord> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            MessageHeaderRecord sample = new MessageHeaderRecord();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<MessageHeaderRecord> retrieveActiveMessageHeaderRecords() {
        return retrieveAll();
    }

    public MO_DeadLetterNotice grabMessageHeaderRecordSummary(String id) {
        MessageHeaderRecord entity = fetchMessageHeaderRecordById(id);
        MO_DeadLetterNotice summary = new MO_DeadLetterNotice();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "MSDGMessageGetter", id, "grabSummary");
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
