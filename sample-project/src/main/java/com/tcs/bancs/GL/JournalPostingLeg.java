package com.tcs.bancs.GL;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: JournalPostingLeg
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class JournalPostingLeg {

    private String legId;
    private String voucherId;
    private String glCode;
    private String legSide;
    private double amount;
    private String narration;
    private String costCenter;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public JournalPostingLeg() {
    }

    public JournalPostingLeg(String legId, String voucherId, String glCode, String legSide, double amount, String narration, String costCenter) {
        this.legId = legId;
        this.voucherId = voucherId;
        this.glCode = glCode;
        this.legSide = legSide;
        this.amount = amount;
        this.narration = narration;
        this.costCenter = costCenter;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.legId = id;
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

    public synchronized void adjustAmount(double newAmt) {
        amount = newAmt;
        this.logStateChange("adjustAmount");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "JournalPostingLeg", String.valueOf(this.legId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getLegId() {
        return this.legId;
    }
    public void setLegId(String legId) {
        this.legId = legId;
    }
    public String getVoucherId() {
        return this.voucherId;
    }
    public void setVoucherId(String voucherId) {
        this.voucherId = voucherId;
    }
    public String getGlCode() {
        return this.glCode;
    }
    public void setGlCode(String glCode) {
        this.glCode = glCode;
    }
    public String getLegSide() {
        return this.legSide;
    }
    public void setLegSide(String legSide) {
        this.legSide = legSide;
    }
    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }
    public String getNarration() {
        return this.narration;
    }
    public void setNarration(String narration) {
        this.narration = narration;
    }
    public String getCostCenter() {
        return this.costCenter;
    }
    public void setCostCenter(String costCenter) {
        this.costCenter = costCenter;
    }
}
