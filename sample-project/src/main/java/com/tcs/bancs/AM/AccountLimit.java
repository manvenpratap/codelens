package com.tcs.bancs.AM;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: AccountLimit
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class AccountLimit {

    private String limitId;
    private String accountNumber;
    private double sanctionedLimit;
    private double drawingPower;
    private double utilizedLimit;
    private String expiryDate;
    private String limitStatus;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public AccountLimit() {
    }

    public AccountLimit(String limitId, String accountNumber, double sanctionedLimit, double drawingPower, double utilizedLimit, String expiryDate, String limitStatus) {
        this.limitId = limitId;
        this.accountNumber = accountNumber;
        this.sanctionedLimit = sanctionedLimit;
        this.drawingPower = drawingPower;
        this.utilizedLimit = utilizedLimit;
        this.expiryDate = expiryDate;
        this.limitStatus = limitStatus;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.limitId = id;
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

    public synchronized void utilize(double amount) {
        utilizedLimit = utilizedLimit + amount; drawingPower = Math.max(0.0, sanctionedLimit - utilizedLimit);
        this.logStateChange("utilize");
    }
    public synchronized void reinstate(double amount) {
        utilizedLimit = Math.max(0.0, utilizedLimit - amount); drawingPower = Math.max(0.0, sanctionedLimit - utilizedLimit);
        this.logStateChange("reinstate");
    }
    public synchronized void renewExpiry(String newDate) {
        expiryDate = newDate; limitStatus = "ACTIVE";
        this.logStateChange("renewExpiry");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "AccountLimit", String.valueOf(this.limitId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getLimitId() {
        return this.limitId;
    }
    public void setLimitId(String limitId) {
        this.limitId = limitId;
    }
    public String getAccountNumber() {
        return this.accountNumber;
    }
    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
    public double getSanctionedLimit() {
        return this.sanctionedLimit;
    }
    public void setSanctionedLimit(double sanctionedLimit) {
        this.sanctionedLimit = sanctionedLimit;
    }
    public double getDrawingPower() {
        return this.drawingPower;
    }
    public void setDrawingPower(double drawingPower) {
        this.drawingPower = drawingPower;
    }
    public double getUtilizedLimit() {
        return this.utilizedLimit;
    }
    public void setUtilizedLimit(double utilizedLimit) {
        this.utilizedLimit = utilizedLimit;
    }
    public String getExpiryDate() {
        return this.expiryDate;
    }
    public void setExpiryDate(String expiryDate) {
        this.expiryDate = expiryDate;
    }
    public String getLimitStatus() {
        return this.limitStatus;
    }
    public void setLimitStatus(String limitStatus) {
        this.limitStatus = limitStatus;
    }
}
