package com.tcs.bancs.DP;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: DPDGInterestLedgerGrabber
 * Specialized query and data retrieval component for DP domain entities.
 */
public class DPDGInterestLedgerGrabber {

    private final Map<String, DepositInterestLedger> entityCache = new ConcurrentHashMap<>();

    public DPDGInterestLedgerGrabber() {
    }

    public DepositInterestLedger fetchDepositInterestLedgerById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            DepositInterestLedger entity = new DepositInterestLedger();
            entity.Get(k);
            return entity;
        });
    }

    public List<DepositInterestLedger> retrieveAll() {
        List<DepositInterestLedger> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            DepositInterestLedger sample = new DepositInterestLedger();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<DepositInterestLedger> retrieveActiveDepositInterestLedgers() {
        return retrieveAll();
    }

    public MO_TdsCertificate grabDepositInterestLedgerSummary(String id) {
        DepositInterestLedger entity = fetchDepositInterestLedgerById(id);
        MO_TdsCertificate summary = new MO_TdsCertificate();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "DPDGInterestLedgerGrabber", id, "grabSummary");
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
