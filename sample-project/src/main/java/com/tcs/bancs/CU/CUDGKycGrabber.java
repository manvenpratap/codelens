package com.tcs.bancs.CU;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: CUDGKycGrabber
 * Specialized query and data retrieval component for CU domain entities.
 */
public class CUDGKycGrabber {

    private final Map<String, KycDocument> entityCache = new ConcurrentHashMap<>();

    public CUDGKycGrabber() {
    }

    public KycDocument fetchKycDocumentById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            KycDocument entity = new KycDocument();
            entity.Get(k);
            return entity;
        });
    }

    public List<KycDocument> retrieveAll() {
        List<KycDocument> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            KycDocument sample = new KycDocument();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<KycDocument> retrieveActiveKycDocuments() {
        return retrieveAll();
    }

    public MO_CustomerCreditScore grabKycDocumentSummary(String id) {
        KycDocument entity = fetchKycDocumentById(id);
        MO_CustomerCreditScore summary = new MO_CustomerCreditScore();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "CUDGKycGrabber", id, "grabSummary");
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
