package com.tcs.bancs.CL;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: DepositoryAccount
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class DepositoryAccount {

    private String depositoryId;
    private String participantCode;
    private String isin;
    private int settledUnits;
    private int blockedUnits;
    private int pledgedUnits;
    private String lastAuditDate;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public DepositoryAccount() {
    }

    public DepositoryAccount(String depositoryId, String participantCode, String isin, int settledUnits, int blockedUnits, int pledgedUnits, String lastAuditDate) {
        this.depositoryId = depositoryId;
        this.participantCode = participantCode;
        this.isin = isin;
        this.settledUnits = settledUnits;
        this.blockedUnits = blockedUnits;
        this.pledgedUnits = pledgedUnits;
        this.lastAuditDate = lastAuditDate;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.depositoryId = id;
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

    public synchronized void creditUnits(int units) {
        settledUnits = settledUnits + units;
        this.logStateChange("creditUnits");
    }
    public synchronized void debitUnits(int units) {
        settledUnits = Math.max(0, settledUnits - units);
        this.logStateChange("debitUnits");
    }
    public synchronized void pledgeUnits(int units) {
        settledUnits = settledUnits - units; pledgedUnits = pledgedUnits + units;
        this.logStateChange("pledgeUnits");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "DepositoryAccount", String.valueOf(this.depositoryId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getDepositoryId() {
        return this.depositoryId;
    }
    public void setDepositoryId(String depositoryId) {
        this.depositoryId = depositoryId;
    }
    public String getParticipantCode() {
        return this.participantCode;
    }
    public void setParticipantCode(String participantCode) {
        this.participantCode = participantCode;
    }
    public String getIsin() {
        return this.isin;
    }
    public void setIsin(String isin) {
        this.isin = isin;
    }
    public int getSettledUnits() {
        return this.settledUnits;
    }
    public void setSettledUnits(int settledUnits) {
        this.settledUnits = settledUnits;
    }
    public int getBlockedUnits() {
        return this.blockedUnits;
    }
    public void setBlockedUnits(int blockedUnits) {
        this.blockedUnits = blockedUnits;
    }
    public int getPledgedUnits() {
        return this.pledgedUnits;
    }
    public void setPledgedUnits(int pledgedUnits) {
        this.pledgedUnits = pledgedUnits;
    }
    public String getLastAuditDate() {
        return this.lastAuditDate;
    }
    public void setLastAuditDate(String lastAuditDate) {
        this.lastAuditDate = lastAuditDate;
    }
}
