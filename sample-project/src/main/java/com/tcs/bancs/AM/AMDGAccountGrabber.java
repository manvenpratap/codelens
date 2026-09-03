package com.tcs.bancs.AM;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: AMDGAccountGrabber
 * Specialized query and data retrieval component for AM domain entities.
 */
public class AMDGAccountGrabber {

    private final Map<String, Account> entityCache = new ConcurrentHashMap<>();

    public AMDGAccountGrabber() {
    }

    public Account fetchAccountById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            Account entity = new Account();
            entity.Get(k);
            return entity;
        });
    }

    public List<Account> retrieveAll() {
        List<Account> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            Account sample = new Account();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<Account> retrieveActiveAccounts() {
        return retrieveAll();
    }

    public MO_AccountSummary grabAccountSummary(String id) {
        Account entity = fetchAccountById(id);
        MO_AccountSummary summary = new MO_AccountSummary();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "AMDGAccountGrabber", id, "grabSummary");
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
