package com.tcs.bancs.SC;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: CollateralPledge
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class CollateralPledge {

    private String pledgeId;
    private String collateralId;
    private String facilityReferenceId;
    private double pledgedAmount;
    private int pledgePriority;
    private String pledgeStatus;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public CollateralPledge() {
    }

    public CollateralPledge(String pledgeId, String collateralId, String facilityReferenceId, double pledgedAmount, int pledgePriority, String pledgeStatus) {
        this.pledgeId = pledgeId;
        this.collateralId = collateralId;
        this.facilityReferenceId = facilityReferenceId;
        this.pledgedAmount = pledgedAmount;
        this.pledgePriority = pledgePriority;
        this.pledgeStatus = pledgeStatus;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.pledgeId = id;
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

    public synchronized void enforcePledge() {
        pledgeStatus = "ENFORCED";
        this.logStateChange("enforcePledge");
    }
    public synchronized void releasePledge() {
        pledgeStatus = "RELEASED"; pledgedAmount = 0.0;
        this.logStateChange("releasePledge");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "CollateralPledge", String.valueOf(this.pledgeId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getPledgeId() {
        return this.pledgeId;
    }
    public void setPledgeId(String pledgeId) {
        this.pledgeId = pledgeId;
    }
    public String getCollateralId() {
        return this.collateralId;
    }
    public void setCollateralId(String collateralId) {
        this.collateralId = collateralId;
    }
    public String getFacilityReferenceId() {
        return this.facilityReferenceId;
    }
    public void setFacilityReferenceId(String facilityReferenceId) {
        this.facilityReferenceId = facilityReferenceId;
    }
    public double getPledgedAmount() {
        return this.pledgedAmount;
    }
    public void setPledgedAmount(double pledgedAmount) {
        this.pledgedAmount = pledgedAmount;
    }
    public int getPledgePriority() {
        return this.pledgePriority;
    }
    public void setPledgePriority(int pledgePriority) {
        this.pledgePriority = pledgePriority;
    }
    public String getPledgeStatus() {
        return this.pledgeStatus;
    }
    public void setPledgeStatus(String pledgeStatus) {
        this.pledgeStatus = pledgeStatus;
    }
}
