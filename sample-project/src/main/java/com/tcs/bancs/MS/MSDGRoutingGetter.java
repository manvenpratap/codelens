package com.tcs.bancs.MS;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: MSDGRoutingGetter
 * Archetype: Paging Cursor Data Getter
 * Specialized query and data retrieval component for MS domain entities.
 */
public class MSDGRoutingGetter {

    private final Map<String, TransformationRule> entityCache = new ConcurrentHashMap<>();

    public MSDGRoutingGetter() {
    }

    /**
     * Type 4: Paging Cursor Data Getter - Cursor lifecycle and paging (Open, Fetch, Close, GetMFwd, GetMBwd).
     */
    private Iterator<TransformationRule> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "MSDGRoutingGetter", criteria, "OPEN");
    }

    public TransformationRule Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "MSDGRoutingGetter", "STATUS", "CLOSED");
    }

    public List<TransformationRule> GetMFwd(String startKey, int pageSize) {
        List<TransformationRule> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<TransformationRule> GetMBwd(String startKey, int pageSize) {
        List<TransformationRule> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public TransformationRule fetchTransformationRuleById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            TransformationRule entity = new TransformationRule();
            entity.Get(k);
            return entity;
        });
    }

    public List<TransformationRule> retrieveAll() {
        List<TransformationRule> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            TransformationRule sample = new TransformationRule();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<TransformationRule> retrieveActiveTransformationRules() {
        return retrieveAll();
    }

    public MO_DeadLetterNotice grabTransformationRuleSummary(String id) {
        TransformationRule entity = fetchTransformationRuleById(id);
        MO_DeadLetterNotice summary = new MO_DeadLetterNotice();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "MSDGRoutingGetter", id, "grabSummary");
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
