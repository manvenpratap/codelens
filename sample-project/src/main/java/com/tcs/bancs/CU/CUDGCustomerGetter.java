package com.tcs.bancs.CU;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: CUDGCustomerGetter
 * Archetype: Simple Data Getter
 * Specialized query and data retrieval component for CU domain entities.
 */
public class CUDGCustomerGetter {

    private final Map<String, CustomerProfile> entityCache = new ConcurrentHashMap<>();

    public CUDGCustomerGetter() {
    }

    /**
     * Type 1: Simple Data Getter - Get() method only.
     */
    public CustomerProfile Get(String id) {
        return fetchCustomerProfileById(id);
    }

    public CustomerProfile fetchCustomerProfileById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            CustomerProfile entity = new CustomerProfile();
            entity.Get(k);
            return entity;
        });
    }

    public List<CustomerProfile> retrieveAll() {
        List<CustomerProfile> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            CustomerProfile sample = new CustomerProfile();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<CustomerProfile> retrieveActiveCustomerProfiles() {
        return retrieveAll();
    }

    public MO_CustomerCreditScore grabCustomerProfileSummary(String id) {
        CustomerProfile entity = fetchCustomerProfileById(id);
        MO_CustomerCreditScore summary = new MO_CustomerCreditScore();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "CUDGCustomerGetter", id, "grabSummary");
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
