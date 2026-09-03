package com.tcs.bancs.PM;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: PMDGPaymentGrabber
 * Specialized query and data retrieval component for PM domain entities.
 */
public class PMDGPaymentGrabber {

    private final Map<String, PaymentTransaction> entityCache = new ConcurrentHashMap<>();

    public PMDGPaymentGrabber() {
    }

    public PaymentTransaction fetchPaymentTransactionById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            PaymentTransaction entity = new PaymentTransaction();
            entity.Get(k);
            return entity;
        });
    }

    public List<PaymentTransaction> retrieveAll() {
        List<PaymentTransaction> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            PaymentTransaction sample = new PaymentTransaction();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<PaymentTransaction> retrieveActivePaymentTransactions() {
        return retrieveAll();
    }

    public MO_LiquidityReservation grabPaymentTransactionSummary(String id) {
        PaymentTransaction entity = fetchPaymentTransactionById(id);
        MO_LiquidityReservation summary = new MO_LiquidityReservation();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "PMDGPaymentGrabber", id, "grabSummary");
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
