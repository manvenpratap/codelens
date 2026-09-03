package com.tcs.bancs.MS;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: MSDGRoutingGrabber
 * Specialized query and data retrieval component for MS domain entities.
 */
public class MSDGRoutingGrabber {

    private final Map<String, TransformationRule> entityCache = new ConcurrentHashMap<>();

    public MSDGRoutingGrabber() {
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
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "MSDGRoutingGrabber", id, "grabSummary");
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
