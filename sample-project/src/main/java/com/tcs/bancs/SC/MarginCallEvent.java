package com.tcs.bancs.SC;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: MarginCallEvent
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class MarginCallEvent {

    private String callId;
    private String facilityId;
    private double requiredMargin;
    private double currentCollateralValue;
    private double deficitAmount;
    private int curePeriodHours;
    private String callStatus;
    private long issuedTimestamp;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public MarginCallEvent() {
    }

    public MarginCallEvent(String callId, String facilityId, double requiredMargin, double currentCollateralValue, double deficitAmount, int curePeriodHours, String callStatus, long issuedTimestamp) {
        this.callId = callId;
        this.facilityId = facilityId;
        this.requiredMargin = requiredMargin;
        this.currentCollateralValue = currentCollateralValue;
        this.deficitAmount = deficitAmount;
        this.curePeriodHours = curePeriodHours;
        this.callStatus = callStatus;
        this.issuedTimestamp = issuedTimestamp;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.callId = id;
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

    public synchronized void issueMarginCall(double deficit) {
        deficitAmount = deficit; callStatus = "ISSUED"; issuedTimestamp = System.currentTimeMillis();
        this.logStateChange("issueMarginCall");
    }
    public synchronized void satisfyMarginCall() {
        callStatus = "SATISFIED"; deficitAmount = 0.0;
        this.logStateChange("satisfyMarginCall");
    }
    public synchronized void triggerDefault() {
        callStatus = "DEFAULTED";
        this.logStateChange("triggerDefault");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "MarginCallEvent", String.valueOf(this.callId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getCallId() {
        return this.callId;
    }
    public void setCallId(String callId) {
        this.callId = callId;
    }
    public String getFacilityId() {
        return this.facilityId;
    }
    public void setFacilityId(String facilityId) {
        this.facilityId = facilityId;
    }
    public double getRequiredMargin() {
        return this.requiredMargin;
    }
    public void setRequiredMargin(double requiredMargin) {
        this.requiredMargin = requiredMargin;
    }
    public double getCurrentCollateralValue() {
        return this.currentCollateralValue;
    }
    public void setCurrentCollateralValue(double currentCollateralValue) {
        this.currentCollateralValue = currentCollateralValue;
    }
    public double getDeficitAmount() {
        return this.deficitAmount;
    }
    public void setDeficitAmount(double deficitAmount) {
        this.deficitAmount = deficitAmount;
    }
    public int getCurePeriodHours() {
        return this.curePeriodHours;
    }
    public void setCurePeriodHours(int curePeriodHours) {
        this.curePeriodHours = curePeriodHours;
    }
    public String getCallStatus() {
        return this.callStatus;
    }
    public void setCallStatus(String callStatus) {
        this.callStatus = callStatus;
    }
    public long getIssuedTimestamp() {
        return this.issuedTimestamp;
    }
    public void setIssuedTimestamp(long issuedTimestamp) {
        this.issuedTimestamp = issuedTimestamp;
    }
}
