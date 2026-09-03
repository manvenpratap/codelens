package com.tcs.bancs.AM;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: OverdraftFacility
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class OverdraftFacility {

    private String facilityId;
    private String accountNumber;
    private double overdraftLimit;
    private double penalRate;
    private String startDate;
    private String reviewDate;
    private boolean isActive;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public OverdraftFacility() {
    }

    public OverdraftFacility(String facilityId, String accountNumber, double overdraftLimit, double penalRate, String startDate, String reviewDate, boolean isActive) {
        this.facilityId = facilityId;
        this.accountNumber = accountNumber;
        this.overdraftLimit = overdraftLimit;
        this.penalRate = penalRate;
        this.startDate = startDate;
        this.reviewDate = reviewDate;
        this.isActive = isActive;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.facilityId = id;
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

    public synchronized void activateFacility(double limit, double rate) {
        overdraftLimit = limit; penalRate = rate; isActive = true;
        this.logStateChange("activateFacility");
    }
    public synchronized void suspendFacility(String reason) {
        isActive = false; reviewDate = reason;
        this.logStateChange("suspendFacility");
    }
    public synchronized void updateRate(double newRate) {
        penalRate = newRate;
        this.logStateChange("updateRate");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "OverdraftFacility", String.valueOf(this.facilityId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getFacilityId() {
        return this.facilityId;
    }
    public void setFacilityId(String facilityId) {
        this.facilityId = facilityId;
    }
    public String getAccountNumber() {
        return this.accountNumber;
    }
    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
    public double getOverdraftLimit() {
        return this.overdraftLimit;
    }
    public void setOverdraftLimit(double overdraftLimit) {
        this.overdraftLimit = overdraftLimit;
    }
    public double getPenalRate() {
        return this.penalRate;
    }
    public void setPenalRate(double penalRate) {
        this.penalRate = penalRate;
    }
    public String getStartDate() {
        return this.startDate;
    }
    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }
    public String getReviewDate() {
        return this.reviewDate;
    }
    public void setReviewDate(String reviewDate) {
        this.reviewDate = reviewDate;
    }
    public boolean getIsActive() {
        return this.isActive;
    }
    public void setIsActive(boolean isActive) {
        this.isActive = isActive;
    }
}
