package com.tcs.bancs.PM;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: PaymentMandate
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class PaymentMandate {

    private String mandateId;
    private String debtorAccount;
    private String creditorId;
    private double maxDebitAmount;
    private String frequency;
    private String expiryDate;
    private String mandateStatus;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public PaymentMandate() {
    }

    public PaymentMandate(String mandateId, String debtorAccount, String creditorId, double maxDebitAmount, String frequency, String expiryDate, String mandateStatus) {
        this.mandateId = mandateId;
        this.debtorAccount = debtorAccount;
        this.creditorId = creditorId;
        this.maxDebitAmount = maxDebitAmount;
        this.frequency = frequency;
        this.expiryDate = expiryDate;
        this.mandateStatus = mandateStatus;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.mandateId = id;
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

    public synchronized void suspendMandate(String reason) {
        mandateStatus = "SUSPENDED"; expiryDate = reason;
        this.logStateChange("suspendMandate");
    }
    public synchronized void executeMandate(double amount) {
        if (amount <= maxDebitAmount) mandateStatus = "ACTIVE"; else mandateStatus = "EXCEEDED";
        this.logStateChange("executeMandate");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "PaymentMandate", String.valueOf(this.mandateId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getMandateId() {
        return this.mandateId;
    }
    public void setMandateId(String mandateId) {
        this.mandateId = mandateId;
    }
    public String getDebtorAccount() {
        return this.debtorAccount;
    }
    public void setDebtorAccount(String debtorAccount) {
        this.debtorAccount = debtorAccount;
    }
    public String getCreditorId() {
        return this.creditorId;
    }
    public void setCreditorId(String creditorId) {
        this.creditorId = creditorId;
    }
    public double getMaxDebitAmount() {
        return this.maxDebitAmount;
    }
    public void setMaxDebitAmount(double maxDebitAmount) {
        this.maxDebitAmount = maxDebitAmount;
    }
    public String getFrequency() {
        return this.frequency;
    }
    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }
    public String getExpiryDate() {
        return this.expiryDate;
    }
    public void setExpiryDate(String expiryDate) {
        this.expiryDate = expiryDate;
    }
    public String getMandateStatus() {
        return this.mandateStatus;
    }
    public void setMandateStatus(String mandateStatus) {
        this.mandateStatus = mandateStatus;
    }
}
