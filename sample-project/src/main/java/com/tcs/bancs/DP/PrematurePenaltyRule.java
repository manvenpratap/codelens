package com.tcs.bancs.DP;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: PrematurePenaltyRule
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class PrematurePenaltyRule {

    private String ruleId;
    private String depositProductCode;
    private int minimumTenureMonths;
    private double penaltyRateDeduction;
    private String effectiveDate;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public PrematurePenaltyRule() {
    }

    public PrematurePenaltyRule(String ruleId, String depositProductCode, int minimumTenureMonths, double penaltyRateDeduction, String effectiveDate) {
        this.ruleId = ruleId;
        this.depositProductCode = depositProductCode;
        this.minimumTenureMonths = minimumTenureMonths;
        this.penaltyRateDeduction = penaltyRateDeduction;
        this.effectiveDate = effectiveDate;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.ruleId = id;
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

    public synchronized void computePenalty(double principal, double rate) {
        penaltyRateDeduction = Math.min(2.0, penaltyRateDeduction);
        this.logStateChange("computePenalty");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "PrematurePenaltyRule", String.valueOf(this.ruleId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getRuleId() {
        return this.ruleId;
    }
    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }
    public String getDepositProductCode() {
        return this.depositProductCode;
    }
    public void setDepositProductCode(String depositProductCode) {
        this.depositProductCode = depositProductCode;
    }
    public int getMinimumTenureMonths() {
        return this.minimumTenureMonths;
    }
    public void setMinimumTenureMonths(int minimumTenureMonths) {
        this.minimumTenureMonths = minimumTenureMonths;
    }
    public double getPenaltyRateDeduction() {
        return this.penaltyRateDeduction;
    }
    public void setPenaltyRateDeduction(double penaltyRateDeduction) {
        this.penaltyRateDeduction = penaltyRateDeduction;
    }
    public String getEffectiveDate() {
        return this.effectiveDate;
    }
    public void setEffectiveDate(String effectiveDate) {
        this.effectiveDate = effectiveDate;
    }
}
