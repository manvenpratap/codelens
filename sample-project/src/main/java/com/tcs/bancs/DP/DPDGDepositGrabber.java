package com.tcs.bancs.DP;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: DPDGDepositGrabber
 * Specialized query and data retrieval component for DP domain entities.
 */
public class DPDGDepositGrabber {

    private final Map<String, DepositContract> entityCache = new ConcurrentHashMap<>();

    public DPDGDepositGrabber() {
    }

    public DepositContract fetchDepositContractById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            DepositContract entity = new DepositContract();
            entity.Get(k);
            return entity;
        });
    }

    public List<DepositContract> retrieveAll() {
        List<DepositContract> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            DepositContract sample = new DepositContract();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<DepositContract> retrieveActiveDepositContracts() {
        return retrieveAll();
    }

    public MO_TdsCertificate grabDepositContractSummary(String id) {
        DepositContract entity = fetchDepositContractById(id);
        MO_TdsCertificate summary = new MO_TdsCertificate();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "DPDGDepositGrabber", id, "grabSummary");
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
