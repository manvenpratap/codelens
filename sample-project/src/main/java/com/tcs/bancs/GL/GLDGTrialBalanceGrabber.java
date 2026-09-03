package com.tcs.bancs.GL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: GLDGTrialBalanceGrabber
 * Specialized query and data retrieval component for GL domain entities.
 */
public class GLDGTrialBalanceGrabber {

    private final Map<String, JournalPostingLeg> entityCache = new ConcurrentHashMap<>();

    public GLDGTrialBalanceGrabber() {
    }

    public JournalPostingLeg fetchJournalPostingLegById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            JournalPostingLeg entity = new JournalPostingLeg();
            entity.Get(k);
            return entity;
        });
    }

    public List<JournalPostingLeg> retrieveAll() {
        List<JournalPostingLeg> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            JournalPostingLeg sample = new JournalPostingLeg();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<JournalPostingLeg> retrieveActiveJournalPostingLegs() {
        return retrieveAll();
    }

    public MO_IntercompanyClearingEntry grabJournalPostingLegSummary(String id) {
        JournalPostingLeg entity = fetchJournalPostingLegById(id);
        MO_IntercompanyClearingEntry summary = new MO_IntercompanyClearingEntry();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "GLDGTrialBalanceGrabber", id, "grabSummary");
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
