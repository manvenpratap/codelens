package com.tcs.bancs.DP;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: DPDGInterestLedgerGetter
 * Archetype: Paging Data Getter
 * Specialized query and data retrieval component for DP domain entities.
 */
public class DPDGInterestLedgerGetter {

    private final Map<String, DepositInterestLedger> entityCache = new ConcurrentHashMap<>();

    public DPDGInterestLedgerGetter() {
    }

    /**
     * Type 2: Paging Data Getter - Forward and backward pagination (GetMFwd, GetMBwd).
     */
    public List<DepositInterestLedger> GetMFwd(String startKey, int pageSize) {
        List<DepositInterestLedger> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<DepositInterestLedger> GetMBwd(String startKey, int pageSize) {
        List<DepositInterestLedger> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
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
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "DPDGInterestLedgerGetter", id, "grabSummary");
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
