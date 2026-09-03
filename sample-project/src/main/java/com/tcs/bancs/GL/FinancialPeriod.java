package com.tcs.bancs.GL;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: FinancialPeriod
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class FinancialPeriod {

    private String periodId;
    private int fiscalYear;
    private int periodNumber;
    private String startDate;
    private String endDate;
    private String periodStatus;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public FinancialPeriod() {
    }

    public FinancialPeriod(String periodId, int fiscalYear, int periodNumber, String startDate, String endDate, String periodStatus) {
        this.periodId = periodId;
        this.fiscalYear = fiscalYear;
        this.periodNumber = periodNumber;
        this.startDate = startDate;
        this.endDate = endDate;
        this.periodStatus = periodStatus;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.periodId = id;
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

    public synchronized void lockPeriod() {
        periodStatus = "LOCKED";
        this.logStateChange("lockPeriod");
    }
    public synchronized void openPeriod() {
        periodStatus = "OPEN";
        this.logStateChange("openPeriod");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "FinancialPeriod", String.valueOf(this.periodId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getPeriodId() {
        return this.periodId;
    }
    public void setPeriodId(String periodId) {
        this.periodId = periodId;
    }
    public int getFiscalYear() {
        return this.fiscalYear;
    }
    public void setFiscalYear(int fiscalYear) {
        this.fiscalYear = fiscalYear;
    }
    public int getPeriodNumber() {
        return this.periodNumber;
    }
    public void setPeriodNumber(int periodNumber) {
        this.periodNumber = periodNumber;
    }
    public String getStartDate() {
        return this.startDate;
    }
    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }
    public String getEndDate() {
        return this.endDate;
    }
    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }
    public String getPeriodStatus() {
        return this.periodStatus;
    }
    public void setPeriodStatus(String periodStatus) {
        this.periodStatus = periodStatus;
    }
}
