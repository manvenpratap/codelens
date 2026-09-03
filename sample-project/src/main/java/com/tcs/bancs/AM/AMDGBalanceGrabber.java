package com.tcs.bancs.AM;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: AMDGBalanceGrabber
 * Specialized query and data retrieval component for AM domain entities.
 */
public class AMDGBalanceGrabber {

    private final Map<String, AccountLimit> entityCache = new ConcurrentHashMap<>();

    public AMDGBalanceGrabber() {
    }

    public AccountLimit fetchAccountLimitById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            AccountLimit entity = new AccountLimit();
            entity.Get(k);
            return entity;
        });
    }

    public List<AccountLimit> retrieveAll() {
        List<AccountLimit> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            AccountLimit sample = new AccountLimit();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<AccountLimit> retrieveActiveAccountLimits() {
        return retrieveAll();
    }

    public MO_AccountSummary grabAccountLimitSummary(String id) {
        AccountLimit entity = fetchAccountLimitById(id);
        MO_AccountSummary summary = new MO_AccountSummary();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "AMDGBalanceGrabber", id, "grabSummary");
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
