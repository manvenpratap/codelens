package com.tcs.bancs.GL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: GLDGVoucherGetter
 * Archetype: Paging Data Getter
 * Specialized query and data retrieval component for GL domain entities.
 */
public class GLDGVoucherGetter {

    private final Map<String, JournalVoucher> entityCache = new ConcurrentHashMap<>();

    public GLDGVoucherGetter() {
    }

    /**
     * Type 2: Paging Data Getter - Forward and backward pagination (GetMFwd, GetMBwd).
     */
    public List<JournalVoucher> GetMFwd(String startKey, int pageSize) {
        List<JournalVoucher> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<JournalVoucher> GetMBwd(String startKey, int pageSize) {
        List<JournalVoucher> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public JournalVoucher fetchJournalVoucherById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            JournalVoucher entity = new JournalVoucher();
            entity.Get(k);
            return entity;
        });
    }

    public List<JournalVoucher> retrieveAll() {
        List<JournalVoucher> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            JournalVoucher sample = new JournalVoucher();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<JournalVoucher> retrieveActiveJournalVouchers() {
        return retrieveAll();
    }

    public MO_IntercompanyClearingEntry grabJournalVoucherSummary(String id) {
        JournalVoucher entity = fetchJournalVoucherById(id);
        MO_IntercompanyClearingEntry summary = new MO_IntercompanyClearingEntry();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "GLDGVoucherGetter", id, "grabSummary");
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
