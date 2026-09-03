package com.tcs.bancs.CL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: CLDGCustodyGrabber
 * Specialized query and data retrieval component for CL domain entities.
 */
public class CLDGCustodyGrabber {

    private final Map<String, DepositoryAccount> entityCache = new ConcurrentHashMap<>();

    public CLDGCustodyGrabber() {
    }

    public DepositoryAccount fetchDepositoryAccountById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            DepositoryAccount entity = new DepositoryAccount();
            entity.Get(k);
            return entity;
        });
    }

    public List<DepositoryAccount> retrieveAll() {
        List<DepositoryAccount> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            DepositoryAccount sample = new DepositoryAccount();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<DepositoryAccount> retrieveActiveDepositoryAccounts() {
        return retrieveAll();
    }

    public MO_FailReport grabDepositoryAccountSummary(String id) {
        DepositoryAccount entity = fetchDepositoryAccountById(id);
        MO_FailReport summary = new MO_FailReport();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "CLDGCustodyGrabber", id, "grabSummary");
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
