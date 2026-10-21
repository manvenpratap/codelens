package com.tcs.bancs.TR;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: TRDGTradeGetter
 * Archetype: Simple Data Getter
 * Specialized query and data retrieval component for TR domain entities.
 */
public class TRDGTradeGetter {

    private final Map<String, OrderEntity> entityCache = new ConcurrentHashMap<>();

    public TRDGTradeGetter() {
    }

    /**
     * Type 1: Simple Data Getter - Get() method only.
     */
    public OrderEntity Get(String id) {
        return fetchOrderEntityById(id);
    }

    public OrderEntity fetchOrderEntityById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            OrderEntity entity = new OrderEntity();
            entity.Get(k);
            return entity;
        });
    }

    public List<OrderEntity> retrieveAll() {
        List<OrderEntity> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            OrderEntity sample = new OrderEntity();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<OrderEntity> retrieveActiveOrderEntitys() {
        return retrieveAll();
    }

    public MO_MarketDepthSnapshot grabOrderEntitySummary(String id) {
        OrderEntity entity = fetchOrderEntityById(id);
        MO_MarketDepthSnapshot summary = new MO_MarketDepthSnapshot();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "TRDGTradeGetter", id, "grabSummary");
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
