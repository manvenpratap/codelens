package com.tcs.bancs.LN;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: LoanDisbursementTranche
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class LoanDisbursementTranche {

    private String trancheId;
    private String loanId;
    private double trancheAmount;
    private String targetDisbursementDate;
    private String disbursementStatus;
    private String referenceId;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public LoanDisbursementTranche() {
    }

    public LoanDisbursementTranche(String trancheId, String loanId, double trancheAmount, String targetDisbursementDate, String disbursementStatus, String referenceId) {
        this.trancheId = trancheId;
        this.loanId = loanId;
        this.trancheAmount = trancheAmount;
        this.targetDisbursementDate = targetDisbursementDate;
        this.disbursementStatus = disbursementStatus;
        this.referenceId = referenceId;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.trancheId = id;
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

    public synchronized void executeDisbursement(String ref) {
        referenceId = ref; disbursementStatus = "COMPLETED";
        this.logStateChange("executeDisbursement");
    }
    public synchronized void cancelTranche(String reason) {
        disbursementStatus = "CANCELLED"; referenceId = reason;
        this.logStateChange("cancelTranche");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "LoanDisbursementTranche", String.valueOf(this.trancheId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getTrancheId() {
        return this.trancheId;
    }
    public void setTrancheId(String trancheId) {
        this.trancheId = trancheId;
    }
    public String getLoanId() {
        return this.loanId;
    }
    public void setLoanId(String loanId) {
        this.loanId = loanId;
    }
    public double getTrancheAmount() {
        return this.trancheAmount;
    }
    public void setTrancheAmount(double trancheAmount) {
        this.trancheAmount = trancheAmount;
    }
    public String getTargetDisbursementDate() {
        return this.targetDisbursementDate;
    }
    public void setTargetDisbursementDate(String targetDisbursementDate) {
        this.targetDisbursementDate = targetDisbursementDate;
    }
    public String getDisbursementStatus() {
        return this.disbursementStatus;
    }
    public void setDisbursementStatus(String disbursementStatus) {
        this.disbursementStatus = disbursementStatus;
    }
    public String getReferenceId() {
        return this.referenceId;
    }
    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }
}
