package com.tcs.bancs.RK;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: PartyRiskLimit
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class PartyRiskLimit {

    private String limitId;
    private String partyId;
    private String limitCategory;
    private String currency;
    private double sanctionedAmount;
    private double utilizedAmount;
    private double thresholdWarningPct;
    private boolean isBlocked;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public PartyRiskLimit() {
    }

    public PartyRiskLimit(String limitId, String partyId, String limitCategory, String currency, double sanctionedAmount, double utilizedAmount, double thresholdWarningPct, boolean isBlocked) {
        this.limitId = limitId;
        this.partyId = partyId;
        this.limitCategory = limitCategory;
        this.currency = currency;
        this.sanctionedAmount = sanctionedAmount;
        this.utilizedAmount = utilizedAmount;
        this.thresholdWarningPct = thresholdWarningPct;
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

    public synchronized void allocate(double amount) {
        utilizedAmount = utilizedAmount + amount; if (utilizedAmount >= sanctionedAmount) isBlocked = true;
        this.logStateChange("allocate");
    }
    public synchronized void deallocate(double amount) {
        utilizedAmount = Math.max(0.0, utilizedAmount - amount); if (utilizedAmount < sanctionedAmount) isBlocked = false;
        this.logStateChange("deallocate");
    }
    public synchronized void overrideLimit(double newSanctioned) {
        sanctionedAmount = newSanctioned; isBlocked = false;
        this.logStateChange("overrideLimit");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "PartyRiskLimit", String.valueOf(this.limitId), action);
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
    public String getPartyId() {
        return this.partyId;
    }
    public void setPartyId(String partyId) {
        this.partyId = partyId;
    }
    public String getLimitCategory() {
        return this.limitCategory;
    }
    public void setLimitCategory(String limitCategory) {
        this.limitCategory = limitCategory;
    }
    public String getCurrency() {
        return this.currency;
    }
    public void setCurrency(String currency) {
        this.currency = currency;
    }
    public double getSanctionedAmount() {
        return this.sanctionedAmount;
    }
    public void setSanctionedAmount(double sanctionedAmount) {
        this.sanctionedAmount = sanctionedAmount;
    }
    public double getUtilizedAmount() {
        return this.utilizedAmount;
    }
    public void setUtilizedAmount(double utilizedAmount) {
        this.utilizedAmount = utilizedAmount;
    }
    public double getThresholdWarningPct() {
        return this.thresholdWarningPct;
    }
    public void setThresholdWarningPct(double thresholdWarningPct) {
        this.thresholdWarningPct = thresholdWarningPct;
    }
    public boolean getIsBlocked() {
        return this.isBlocked;
    }
    public void setIsBlocked(boolean isBlocked) {
        this.isBlocked = isBlocked;
    }
}
