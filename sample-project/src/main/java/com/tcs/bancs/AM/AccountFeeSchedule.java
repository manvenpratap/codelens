package com.tcs.bancs.AM;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: AccountFeeSchedule
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class AccountFeeSchedule {

    private String scheduleId;
    private String accountType;
    private double monthlyFee;
    private double transactionCharge;
    private double minBalanceThreshold;
    private String waiverCode;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public AccountFeeSchedule() {
    }

    public AccountFeeSchedule(String scheduleId, String accountType, double monthlyFee, double transactionCharge, double minBalanceThreshold, String waiverCode) {
        this.scheduleId = scheduleId;
        this.accountType = accountType;
        this.monthlyFee = monthlyFee;
        this.transactionCharge = transactionCharge;
        this.minBalanceThreshold = minBalanceThreshold;
        this.waiverCode = waiverCode;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.scheduleId = id;
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

    public synchronized void applyWaiver(String code) {
        waiverCode = code; monthlyFee = 0.0;
        this.logStateChange("applyWaiver");
    }
    public synchronized void updateCharges(double fee, double charge) {
        monthlyFee = fee; transactionCharge = charge;
        this.logStateChange("updateCharges");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "AccountFeeSchedule", String.valueOf(this.scheduleId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getScheduleId() {
        return this.scheduleId;
    }
    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }
    public String getAccountType() {
        return this.accountType;
    }
    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }
    public double getMonthlyFee() {
        return this.monthlyFee;
    }
    public void setMonthlyFee(double monthlyFee) {
        this.monthlyFee = monthlyFee;
    }
    public double getTransactionCharge() {
        return this.transactionCharge;
    }
    public void setTransactionCharge(double transactionCharge) {
        this.transactionCharge = transactionCharge;
    }
    public double getMinBalanceThreshold() {
        return this.minBalanceThreshold;
    }
    public void setMinBalanceThreshold(double minBalanceThreshold) {
        this.minBalanceThreshold = minBalanceThreshold;
    }
    public String getWaiverCode() {
        return this.waiverCode;
    }
    public void setWaiverCode(String waiverCode) {
        this.waiverCode = waiverCode;
    }
}
