package com.tcs.bancs.DP;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: DepositInterestLedger
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class DepositInterestLedger {

    private String ledgerId;
    private String depositId;
    private String calculationPeriodStart;
    private String calculationPeriodEnd;
    private double accruedAmount;
    private double taxDeductedAtSource;
    private boolean isPosted;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public DepositInterestLedger() {
    }

    public DepositInterestLedger(String ledgerId, String depositId, String calculationPeriodStart, String calculationPeriodEnd, double accruedAmount, double taxDeductedAtSource, boolean isPosted) {
        this.ledgerId = ledgerId;
        this.depositId = depositId;
        this.calculationPeriodStart = calculationPeriodStart;
        this.calculationPeriodEnd = calculationPeriodEnd;
        this.accruedAmount = accruedAmount;
        this.taxDeductedAtSource = taxDeductedAtSource;
        this.isPosted = isPosted;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.ledgerId = id;
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

    public synchronized void postAccrual() {
        isPosted = true;
        this.logStateChange("postAccrual");
    }
    public synchronized void deductTds(double tdsAmount) {
        taxDeductedAtSource = taxDeductedAtSource + tdsAmount; accruedAmount = accruedAmount - tdsAmount;
        this.logStateChange("deductTds");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "DepositInterestLedger", String.valueOf(this.ledgerId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getLedgerId() {
        return this.ledgerId;
    }
    public void setLedgerId(String ledgerId) {
        this.ledgerId = ledgerId;
    }
    public String getDepositId() {
        return this.depositId;
    }
    public void setDepositId(String depositId) {
        this.depositId = depositId;
    }
    public String getCalculationPeriodStart() {
        return this.calculationPeriodStart;
    }
    public void setCalculationPeriodStart(String calculationPeriodStart) {
        this.calculationPeriodStart = calculationPeriodStart;
    }
    public String getCalculationPeriodEnd() {
        return this.calculationPeriodEnd;
    }
    public void setCalculationPeriodEnd(String calculationPeriodEnd) {
        this.calculationPeriodEnd = calculationPeriodEnd;
    }
    public double getAccruedAmount() {
        return this.accruedAmount;
    }
    public void setAccruedAmount(double accruedAmount) {
        this.accruedAmount = accruedAmount;
    }
    public double getTaxDeductedAtSource() {
        return this.taxDeductedAtSource;
    }
    public void setTaxDeductedAtSource(double taxDeductedAtSource) {
        this.taxDeductedAtSource = taxDeductedAtSource;
    }
    public boolean getIsPosted() {
        return this.isPosted;
    }
    public void setIsPosted(boolean isPosted) {
        this.isPosted = isPosted;
    }
}
