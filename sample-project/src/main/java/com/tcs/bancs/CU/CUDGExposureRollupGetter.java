package com.tcs.bancs.CU;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: CUDGExposureRollupGetter
 * Archetype: Paging Cursor Data Getter
 * Specialized query and data retrieval component for CU domain entities.
 */
public class CUDGExposureRollupGetter {

    private final Map<String, CustomerPepScreening> entityCache = new ConcurrentHashMap<>();

    public CUDGExposureRollupGetter() {
    }

    /**
     * Type 4: Paging Cursor Data Getter - Cursor lifecycle and paging (Open, Fetch, Close, GetMFwd, GetMBwd).
     */
    private Iterator<CustomerPepScreening> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "CUDGExposureRollupGetter", criteria, "OPEN");
    }

    public CustomerPepScreening Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "CUDGExposureRollupGetter", "STATUS", "CLOSED");
    }

    public List<CustomerPepScreening> GetMFwd(String startKey, int pageSize) {
        List<CustomerPepScreening> results = retrieveAll();
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public List<CustomerPepScreening> GetMBwd(String startKey, int pageSize) {
        List<CustomerPepScreening> results = retrieveAll();
        Collections.reverse(results);
        int limit = Math.max(1, Math.min(pageSize, results.size()));
        return new ArrayList<>(results.subList(0, limit));
    }

    public CustomerPepScreening fetchCustomerPepScreeningById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            CustomerPepScreening entity = new CustomerPepScreening();
            entity.Get(k);
            return entity;
        });
    }

    public List<CustomerPepScreening> retrieveAll() {
        List<CustomerPepScreening> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            CustomerPepScreening sample = new CustomerPepScreening();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<CustomerPepScreening> retrieveActiveCustomerPepScreenings() {
        return retrieveAll();
    }

    public MO_CustomerCreditScore grabCustomerPepScreeningSummary(String id) {
        CustomerPepScreening entity = fetchCustomerPepScreeningById(id);
        MO_CustomerCreditScore summary = new MO_CustomerCreditScore();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "CUDGExposureRollupGetter", id, "grabSummary");
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
