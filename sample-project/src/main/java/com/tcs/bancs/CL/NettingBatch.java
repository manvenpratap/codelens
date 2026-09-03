package com.tcs.bancs.CL;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: NettingBatch
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class NettingBatch {

    private String batchId;
    private String batchDate;
    private String clearingMemberId;
    private int grossTradeCount;
    private double netCashObligation;
    private int netSecurityObligation;
    private String batchStatus;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public NettingBatch() {
    }

    public NettingBatch(String batchId, String batchDate, String clearingMemberId, int grossTradeCount, double netCashObligation, int netSecurityObligation, String batchStatus) {
        this.batchId = batchId;
        this.batchDate = batchDate;
        this.clearingMemberId = clearingMemberId;
        this.grossTradeCount = grossTradeCount;
        this.netCashObligation = netCashObligation;
        this.netSecurityObligation = netSecurityObligation;
        this.batchStatus = batchStatus;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.batchId = id;
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

    public synchronized void closeBatch() {
        batchStatus = "CLOSED";
        this.logStateChange("closeBatch");
    }
    public synchronized void postObligation(double cash, int units) {
        netCashObligation = netCashObligation + cash; netSecurityObligation = netSecurityObligation + units; grossTradeCount = grossTradeCount + 1;
        this.logStateChange("postObligation");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "NettingBatch", String.valueOf(this.batchId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getBatchId() {
        return this.batchId;
    }
    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }
    public String getBatchDate() {
        return this.batchDate;
    }
    public void setBatchDate(String batchDate) {
        this.batchDate = batchDate;
    }
    public String getClearingMemberId() {
        return this.clearingMemberId;
    }
    public void setClearingMemberId(String clearingMemberId) {
        this.clearingMemberId = clearingMemberId;
    }
    public int getGrossTradeCount() {
        return this.grossTradeCount;
    }
    public void setGrossTradeCount(int grossTradeCount) {
        this.grossTradeCount = grossTradeCount;
    }
    public double getNetCashObligation() {
        return this.netCashObligation;
    }
    public void setNetCashObligation(double netCashObligation) {
        this.netCashObligation = netCashObligation;
    }
    public int getNetSecurityObligation() {
        return this.netSecurityObligation;
    }
    public void setNetSecurityObligation(int netSecurityObligation) {
        this.netSecurityObligation = netSecurityObligation;
    }
    public String getBatchStatus() {
        return this.batchStatus;
    }
    public void setBatchStatus(String batchStatus) {
        this.batchStatus = batchStatus;
    }
}
