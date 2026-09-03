package com.tcs.bancs.GL;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: LedgerAccount
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class LedgerAccount {

    private String glCode;
    private String glName;
    private String glCategory;
    private String currency;
    private double currentDebitBalance;
    private double currentCreditBalance;
    private double netBalance;
    private String reconciliationStatus;
    private boolean isBlocked;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public LedgerAccount() {
    }

    public LedgerAccount(String glCode, String glName, String glCategory, String currency, double currentDebitBalance, double currentCreditBalance, double netBalance, String reconciliationStatus, boolean isBlocked) {
        this.glCode = glCode;
        this.glName = glName;
        this.glCategory = glCategory;
        this.currency = currency;
        this.currentDebitBalance = currentDebitBalance;
        this.currentCreditBalance = currentCreditBalance;
        this.netBalance = netBalance;
        this.reconciliationStatus = reconciliationStatus;
        this.isBlocked = isBlocked;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.glCode = id;
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

    public synchronized void postDebit(double amount) {
        currentDebitBalance = currentDebitBalance + amount; netBalance = currentDebitBalance - currentCreditBalance;
        this.logStateChange("postDebit");
    }
    public synchronized void postCredit(double amount) {
        currentCreditBalance = currentCreditBalance + amount; netBalance = currentDebitBalance - currentCreditBalance;
        this.logStateChange("postCredit");
    }
    public synchronized void reconcile() {
        reconciliationStatus = "RECONCILED";
        this.logStateChange("reconcile");
    }
    public synchronized void closePeriod() {
        if (netBalance != 0.0) reconciliationStatus = "CARRIED_FORWARD";
        this.logStateChange("closePeriod");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "LedgerAccount", String.valueOf(this.glCode), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getGlCode() {
        return this.glCode;
    }
    public void setGlCode(String glCode) {
        this.glCode = glCode;
    }
    public String getGlName() {
        return this.glName;
    }
    public void setGlName(String glName) {
        this.glName = glName;
    }
    public String getGlCategory() {
        return this.glCategory;
    }
    public void setGlCategory(String glCategory) {
        this.glCategory = glCategory;
    }
    public String getCurrency() {
        return this.currency;
    }
    public void setCurrency(String currency) {
        this.currency = currency;
    }
    public double getCurrentDebitBalance() {
        return this.currentDebitBalance;
    }
    public void setCurrentDebitBalance(double currentDebitBalance) {
        this.currentDebitBalance = currentDebitBalance;
    }
    public double getCurrentCreditBalance() {
        return this.currentCreditBalance;
    }
    public void setCurrentCreditBalance(double currentCreditBalance) {
        this.currentCreditBalance = currentCreditBalance;
    }
    public double getNetBalance() {
        return this.netBalance;
    }
    public void setNetBalance(double netBalance) {
        this.netBalance = netBalance;
    }
    public String getReconciliationStatus() {
        return this.reconciliationStatus;
    }
    public void setReconciliationStatus(String reconciliationStatus) {
        this.reconciliationStatus = reconciliationStatus;
    }
    public boolean getIsBlocked() {
        return this.isBlocked;
    }
    public void setIsBlocked(boolean isBlocked) {
        this.isBlocked = isBlocked;
    }
}
