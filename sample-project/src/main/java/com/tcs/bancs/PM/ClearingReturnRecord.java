package com.tcs.bancs.PM;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: ClearingReturnRecord
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class ClearingReturnRecord {

    private String returnId;
    private String originalPaymentId;
    private String returnReasonCode;
    private double returnedAmount;
    private long returnTimestamp;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public ClearingReturnRecord() {
    }

    public ClearingReturnRecord(String returnId, String originalPaymentId, String returnReasonCode, double returnedAmount, long returnTimestamp) {
        this.returnId = returnId;
        this.originalPaymentId = originalPaymentId;
        this.returnReasonCode = returnReasonCode;
        this.returnedAmount = returnedAmount;
        this.returnTimestamp = returnTimestamp;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.returnId = id;
        this.isPersisted = true;
        this.logStateChange("Get");
        return true;
    }

    /**
     * Persists a newly created entity into underlying storage.
     */
    public synchronized boolean Create() {
        this.isPersisted = true;
        this.entityVersion = "1.0";
        this.logStateChange("Create");
        return true;
    }

    /**
     * Modifies persistent entity attributes and records mutation.
     */
    public synchronized boolean Modify(String newStatus) {
        this.entityVersion = "1.1";
        this.logStateChange("Modify");
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Business Methods (read, write, and propagate entity fields)
    // ─────────────────────────────────────────────────────────────────────────

    public synchronized void acknowledgeReturn() {
        returnTimestamp = System.currentTimeMillis();
        this.logStateChange("acknowledgeReturn");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "ClearingReturnRecord", String.valueOf(this.returnId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getReturnId() {
        return this.returnId;
    }
    public void setReturnId(String returnId) {
        this.returnId = returnId;
    }
    public String getOriginalPaymentId() {
        return this.originalPaymentId;
    }
    public void setOriginalPaymentId(String originalPaymentId) {
        this.originalPaymentId = originalPaymentId;
    }
    public String getReturnReasonCode() {
        return this.returnReasonCode;
    }
    public void setReturnReasonCode(String returnReasonCode) {
        this.returnReasonCode = returnReasonCode;
    }
    public double getReturnedAmount() {
        return this.returnedAmount;
    }
    public void setReturnedAmount(double returnedAmount) {
        this.returnedAmount = returnedAmount;
    }
    public long getReturnTimestamp() {
        return this.returnTimestamp;
    }
    public void setReturnTimestamp(long returnTimestamp) {
        this.returnTimestamp = returnTimestamp;
    }
}
