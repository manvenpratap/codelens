package com.tcs.bancs.DP;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: DPDGPenaltyRuleGetter
 * Archetype: Cursor Data Getter
 * Specialized query and data retrieval component for DP domain entities.
 */
public class DPDGPenaltyRuleGetter {

    private final Map<String, PrematurePenaltyRule> entityCache = new ConcurrentHashMap<>();

    public DPDGPenaltyRuleGetter() {
    }

    /**
     * Type 3: Cursor Data Getter - Streaming cursor lifecycle (Open, Fetch, Close).
     */
    private Iterator<PrematurePenaltyRule> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "DPDGPenaltyRuleGetter", criteria, "OPEN");
    }

    public PrematurePenaltyRule Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "DPDGPenaltyRuleGetter", "STATUS", "CLOSED");
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
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "DPDGPenaltyRuleGetter", id, "grabSummary");
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
