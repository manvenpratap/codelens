package com.tcs.bancs.PM;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: RoutingDirectory
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class RoutingDirectory {

    private String directoryId;
    private String bankCode;
    private String bic;
    private String supportedNetwork;
    private String cutoffTimeUtc;
    private boolean isDirectParticipant;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public RoutingDirectory() {
    }

    public RoutingDirectory(String directoryId, String bankCode, String bic, String supportedNetwork, String cutoffTimeUtc, boolean isDirectParticipant) {
        this.directoryId = directoryId;
        this.bankCode = bankCode;
        this.bic = bic;
        this.supportedNetwork = supportedNetwork;
        this.cutoffTimeUtc = cutoffTimeUtc;
        this.isDirectParticipant = isDirectParticipant;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.directoryId = id;
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

    public synchronized void checkAvailability(String time) {
        if (cutoffTimeUtc != null && cutoffTimeUtc.compareTo(time) > 0) isDirectParticipant = true;
        this.logStateChange("checkAvailability");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "RoutingDirectory", String.valueOf(this.directoryId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getDirectoryId() {
        return this.directoryId;
    }
    public void setDirectoryId(String directoryId) {
        this.directoryId = directoryId;
    }
    public String getBankCode() {
        return this.bankCode;
    }
    public void setBankCode(String bankCode) {
        this.bankCode = bankCode;
    }
    public String getBic() {
        return this.bic;
    }
    public void setBic(String bic) {
        this.bic = bic;
    }
    public String getSupportedNetwork() {
        return this.supportedNetwork;
    }
    public void setSupportedNetwork(String supportedNetwork) {
        this.supportedNetwork = supportedNetwork;
    }
    public String getCutoffTimeUtc() {
        return this.cutoffTimeUtc;
    }
    public void setCutoffTimeUtc(String cutoffTimeUtc) {
        this.cutoffTimeUtc = cutoffTimeUtc;
    }
    public boolean getIsDirectParticipant() {
        return this.isDirectParticipant;
    }
    public void setIsDirectParticipant(boolean isDirectParticipant) {
        this.isDirectParticipant = isDirectParticipant;
    }
}
