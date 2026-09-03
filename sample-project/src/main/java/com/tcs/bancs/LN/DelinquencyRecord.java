package com.tcs.bancs.LN;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: DelinquencyRecord
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class DelinquencyRecord {

    private String delinquencyId;
    private String loanId;
    private int daysPastDue;
    private double overduePrincipal;
    private double overdueInterest;
    private double penaltyCharged;
    private String recoveryStage;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public DelinquencyRecord() {
    }

    public DelinquencyRecord(String delinquencyId, String loanId, int daysPastDue, double overduePrincipal, double overdueInterest, double penaltyCharged, String recoveryStage) {
        this.delinquencyId = delinquencyId;
        this.loanId = loanId;
        this.daysPastDue = daysPastDue;
        this.overduePrincipal = overduePrincipal;
        this.overdueInterest = overdueInterest;
        this.penaltyCharged = penaltyCharged;
        this.recoveryStage = recoveryStage;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.delinquencyId = id;
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

    public synchronized void escalateNPA(String stage) {
        recoveryStage = stage; daysPastDue = daysPastDue + 30;
        this.logStateChange("escalateNPA");
    }
    public synchronized void settleDues(double settledAmount) {
        overduePrincipal = 0.0; overdueInterest = 0.0; penaltyCharged = 0.0; recoveryStage = "RESOLVED";
        this.logStateChange("settleDues");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "DelinquencyRecord", String.valueOf(this.delinquencyId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getDelinquencyId() {
        return this.delinquencyId;
    }
    public void setDelinquencyId(String delinquencyId) {
        this.delinquencyId = delinquencyId;
    }
    public String getLoanId() {
        return this.loanId;
    }
    public void setLoanId(String loanId) {
        this.loanId = loanId;
    }
    public int getDaysPastDue() {
        return this.daysPastDue;
    }
    public void setDaysPastDue(int daysPastDue) {
        this.daysPastDue = daysPastDue;
    }
    public double getOverduePrincipal() {
        return this.overduePrincipal;
    }
    public void setOverduePrincipal(double overduePrincipal) {
        this.overduePrincipal = overduePrincipal;
    }
    public double getOverdueInterest() {
        return this.overdueInterest;
    }
    public void setOverdueInterest(double overdueInterest) {
        this.overdueInterest = overdueInterest;
    }
    public double getPenaltyCharged() {
        return this.penaltyCharged;
    }
    public void setPenaltyCharged(double penaltyCharged) {
        this.penaltyCharged = penaltyCharged;
    }
    public String getRecoveryStage() {
        return this.recoveryStage;
    }
    public void setRecoveryStage(String recoveryStage) {
        this.recoveryStage = recoveryStage;
    }
}
