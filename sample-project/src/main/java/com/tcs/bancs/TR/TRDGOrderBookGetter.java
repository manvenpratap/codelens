package com.tcs.bancs.TR;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: TRDGOrderBookGetter
 * Archetype: Paging Data Getter
 * Specialized query and data retrieval component for TR domain entities.
 */
public class TRDGOrderBookGetter {

    private final Map<String, TradeExecution> entityCache = new ConcurrentHashMap<>();

    public TRDGOrderBookGetter() {
    }

    /**
     * Type 2: Paging Data Getter - Forward and backward pagination (GetMFwd, GetMBwd).
     */
    public List<TradeExecution> GetMFwd(String startKey, int pageSize) {
        List<TradeExecution> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<TradeExecution> GetMBwd(String startKey, int pageSize) {
        List<TradeExecution> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public TradeExecution fetchTradeExecutionById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            TradeExecution entity = new TradeExecution();
            entity.Get(k);
            return entity;
        });
    }

    public List<TradeExecution> retrieveAll() {
        List<TradeExecution> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            TradeExecution sample = new TradeExecution();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<TradeExecution> retrieveActiveTradeExecutions() {
        return retrieveAll();
    }

    public MO_MarketDepthSnapshot grabTradeExecutionSummary(String id) {
        TradeExecution entity = fetchTradeExecutionById(id);
        MO_MarketDepthSnapshot summary = new MO_MarketDepthSnapshot();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "TRDGOrderBookGetter", id, "grabSummary");
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
