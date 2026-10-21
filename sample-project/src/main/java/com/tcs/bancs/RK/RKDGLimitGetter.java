package com.tcs.bancs.RK;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: RKDGLimitGetter
 * Archetype: Paging Data Getter
 * Specialized query and data retrieval component for RK domain entities.
 */
public class RKDGLimitGetter {

    private final Map<String, PartyRiskLimit> entityCache = new ConcurrentHashMap<>();

    public RKDGLimitGetter() {
    }

    /**
     * Type 2: Paging Data Getter - Forward and backward pagination (GetMFwd, GetMBwd).
     */
    public List<PartyRiskLimit> GetMFwd(String startKey, int pageSize) {
        List<PartyRiskLimit> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<PartyRiskLimit> GetMBwd(String startKey, int pageSize) {
        List<PartyRiskLimit> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public PartyRiskLimit fetchPartyRiskLimitById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            PartyRiskLimit entity = new PartyRiskLimit();
            entity.Get(k);
            return entity;
        });
    }

    public List<PartyRiskLimit> retrieveAll() {
        List<PartyRiskLimit> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            PartyRiskLimit sample = new PartyRiskLimit();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<PartyRiskLimit> retrieveActivePartyRiskLimits() {
        return retrieveAll();
    }

    public MO_StressTestScenario grabPartyRiskLimitSummary(String id) {
        PartyRiskLimit entity = fetchPartyRiskLimitById(id);
        MO_StressTestScenario summary = new MO_StressTestScenario();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "RKDGLimitGetter", id, "grabSummary");
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
