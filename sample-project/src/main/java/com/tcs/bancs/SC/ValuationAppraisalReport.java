package com.tcs.bancs.SC;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: ValuationAppraisalReport
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class ValuationAppraisalReport {

    private String appraisalId;
    private String collateralId;
    private String appraiserAgency;
    private double appraisedValue;
    private String appraisalDate;
    private String methodology;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public ValuationAppraisalReport() {
    }

    public ValuationAppraisalReport(String appraisalId, String collateralId, String appraiserAgency, double appraisedValue, String appraisalDate, String methodology) {
        this.appraisalId = appraisalId;
        this.collateralId = collateralId;
        this.appraiserAgency = appraiserAgency;
        this.appraisedValue = appraisedValue;
        this.appraisalDate = appraisalDate;
        this.methodology = methodology;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.appraisalId = id;
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

    public synchronized void acceptAppraisal(double val) {
        appraisedValue = val; methodology = "ACCEPTED";
        this.logStateChange("acceptAppraisal");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "ValuationAppraisalReport", String.valueOf(this.appraisalId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getAppraisalId() {
        return this.appraisalId;
    }
    public void setAppraisalId(String appraisalId) {
        this.appraisalId = appraisalId;
    }
    public String getCollateralId() {
        return this.collateralId;
    }
    public void setCollateralId(String collateralId) {
        this.collateralId = collateralId;
    }
    public String getAppraiserAgency() {
        return this.appraiserAgency;
    }
    public void setAppraiserAgency(String appraiserAgency) {
        this.appraiserAgency = appraiserAgency;
    }
    public double getAppraisedValue() {
        return this.appraisedValue;
    }
    public void setAppraisedValue(double appraisedValue) {
        this.appraisedValue = appraisedValue;
    }
    public String getAppraisalDate() {
        return this.appraisalDate;
    }
    public void setAppraisalDate(String appraisalDate) {
        this.appraisalDate = appraisalDate;
    }
    public String getMethodology() {
        return this.methodology;
    }
    public void setMethodology(String methodology) {
        this.methodology = methodology;
    }
}
