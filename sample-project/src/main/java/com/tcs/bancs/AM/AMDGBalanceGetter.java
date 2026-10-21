package com.tcs.bancs.AM;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: AMDGBalanceGetter
 * Archetype: Paging Data Getter
 * Specialized query and data retrieval component for AM domain entities.
 */
public class AMDGBalanceGetter {

    private final Map<String, AccountLimit> entityCache = new ConcurrentHashMap<>();

    public AMDGBalanceGetter() {
    }

    /**
     * Type 2: Paging Data Getter - Forward and backward pagination (GetMFwd, GetMBwd).
     */
    public List<AccountLimit> GetMFwd(String startKey, int pageSize) {
        List<AccountLimit> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<AccountLimit> GetMBwd(String startKey, int pageSize) {
        List<AccountLimit> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
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
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "AMDGBalanceGetter", id, "grabSummary");
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
