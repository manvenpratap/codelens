package com.tcs.bancs.SC;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: SCDGCollateralGetter
 * Archetype: Simple Data Getter
 * Specialized query and data retrieval component for SC domain entities.
 */
public class SCDGCollateralGetter {

    private final Map<String, CollateralItem> entityCache = new ConcurrentHashMap<>();

    public SCDGCollateralGetter() {
    }

    /**
     * Type 1: Simple Data Getter - Get() method only.
     */
    public CollateralItem Get(String id) {
        return fetchCollateralItemById(id);
    }

    public CollateralItem fetchCollateralItemById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            CollateralItem entity = new CollateralItem();
            entity.Get(k);
            return entity;
        });
    }

    public List<CollateralItem> retrieveAll() {
        List<CollateralItem> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            CollateralItem sample = new CollateralItem();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<CollateralItem> retrieveActiveCollateralItems() {
        return retrieveAll();
    }

    public MO_CollateralReleaseRequest grabCollateralItemSummary(String id) {
        CollateralItem entity = fetchCollateralItemById(id);
        MO_CollateralReleaseRequest summary = new MO_CollateralReleaseRequest();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "SCDGCollateralGetter", id, "grabSummary");
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
