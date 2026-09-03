package com.tcs.bancs.DP;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: DPDGPenaltyRuleGrabber
 * Specialized query and data retrieval component for DP domain entities.
 */
public class DPDGPenaltyRuleGrabber {

    private final Map<String, PrematurePenaltyRule> entityCache = new ConcurrentHashMap<>();

    public DPDGPenaltyRuleGrabber() {
    }

    public PrematurePenaltyRule fetchPrematurePenaltyRuleById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            PrematurePenaltyRule entity = new PrematurePenaltyRule();
            entity.Get(k);
            return entity;
        });
    }

    public List<PrematurePenaltyRule> retrieveAll() {
        List<PrematurePenaltyRule> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            PrematurePenaltyRule sample = new PrematurePenaltyRule();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<PrematurePenaltyRule> retrieveActivePrematurePenaltyRules() {
        return retrieveAll();
    }

    public MO_TdsCertificate grabPrematurePenaltyRuleSummary(String id) {
        PrematurePenaltyRule entity = fetchPrematurePenaltyRuleById(id);
        MO_TdsCertificate summary = new MO_TdsCertificate();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "DPDGPenaltyRuleGrabber", id, "grabSummary");
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
