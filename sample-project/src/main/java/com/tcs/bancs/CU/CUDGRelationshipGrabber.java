package com.tcs.bancs.CU;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: CUDGRelationshipGrabber
 * Specialized query and data retrieval component for CU domain entities.
 */
public class CUDGRelationshipGrabber {

    private final Map<String, PartyRelationship> entityCache = new ConcurrentHashMap<>();

    public CUDGRelationshipGrabber() {
    }

    public PartyRelationship fetchPartyRelationshipById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            PartyRelationship entity = new PartyRelationship();
            entity.Get(k);
            return entity;
        });
    }

    public List<PartyRelationship> retrieveAll() {
        List<PartyRelationship> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            PartyRelationship sample = new PartyRelationship();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<PartyRelationship> retrieveActivePartyRelationships() {
        return retrieveAll();
    }

    public MO_CustomerCreditScore grabPartyRelationshipSummary(String id) {
        PartyRelationship entity = fetchPartyRelationshipById(id);
        MO_CustomerCreditScore summary = new MO_CustomerCreditScore();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "CUDGRelationshipGrabber", id, "grabSummary");
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
