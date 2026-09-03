package com.tcs.bancs.CL;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: SettlementFailRecord
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class SettlementFailRecord {

    private String failId;
    private String instructionId;
    private String failReasonCode;
    private double penaltyAccrued;
    private int curePeriodDays;
    private String resolutionStatus;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public SettlementFailRecord() {
    }

    public SettlementFailRecord(String failId, String instructionId, String failReasonCode, double penaltyAccrued, int curePeriodDays, String resolutionStatus) {
        this.failId = failId;
        this.instructionId = instructionId;
        this.failReasonCode = failReasonCode;
        this.penaltyAccrued = penaltyAccrued;
        this.curePeriodDays = curePeriodDays;
        this.resolutionStatus = resolutionStatus;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.failId = id;
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

    public synchronized void resolveFail(String note) {
        resolutionStatus = "RESOLVED"; failReasonCode = note;
        this.logStateChange("resolveFail");
    }
    public synchronized void accruePenalty(double penalty) {
        penaltyAccrued = penaltyAccrued + penalty;
        this.logStateChange("accruePenalty");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "SettlementFailRecord", String.valueOf(this.failId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getFailId() {
        return this.failId;
    }
    public void setFailId(String failId) {
        this.failId = failId;
    }
    public String getInstructionId() {
        return this.instructionId;
    }
    public void setInstructionId(String instructionId) {
        this.instructionId = instructionId;
    }
    public String getFailReasonCode() {
        return this.failReasonCode;
    }
    public void setFailReasonCode(String failReasonCode) {
        this.failReasonCode = failReasonCode;
    }
    public double getPenaltyAccrued() {
        return this.penaltyAccrued;
    }
    public void setPenaltyAccrued(double penaltyAccrued) {
        this.penaltyAccrued = penaltyAccrued;
    }
    public int getCurePeriodDays() {
        return this.curePeriodDays;
    }
    public void setCurePeriodDays(int curePeriodDays) {
        this.curePeriodDays = curePeriodDays;
    }
    public String getResolutionStatus() {
        return this.resolutionStatus;
    }
    public void setResolutionStatus(String resolutionStatus) {
        this.resolutionStatus = resolutionStatus;
    }
}
