package com.tcs.bancs.PM;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: PMDGMandateGetter
 * Archetype: Cursor Data Getter
 * Specialized query and data retrieval component for PM domain entities.
 */
public class PMDGMandateGetter {

    private final Map<String, PaymentMandate> entityCache = new ConcurrentHashMap<>();

    public PMDGMandateGetter() {
    }

    /**
     * Type 3: Cursor Data Getter - Streaming cursor lifecycle (Open, Fetch, Close).
     */
    private Iterator<PaymentMandate> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "PMDGMandateGetter", criteria, "OPEN");
    }

    public PaymentMandate Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "PMDGMandateGetter", "STATUS", "CLOSED");
    }

    public PaymentMandate fetchPaymentMandateById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            PaymentMandate entity = new PaymentMandate();
            entity.Get(k);
            return entity;
        });
    }

    public List<PaymentMandate> retrieveAll() {
        List<PaymentMandate> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            PaymentMandate sample = new PaymentMandate();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<PaymentMandate> retrieveActivePaymentMandates() {
        return retrieveAll();
    }

    public MO_LiquidityReservation grabPaymentMandateSummary(String id) {
        PaymentMandate entity = fetchPaymentMandateById(id);
        MO_LiquidityReservation summary = new MO_LiquidityReservation();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "PMDGMandateGetter", id, "grabSummary");
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
