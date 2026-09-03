package com.tcs.bancs.CU;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: KycDocument
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class KycDocument {

    private String documentId;
    private String customerId;
    private String documentType;
    private String documentNumber;
    private String issuingAuthority;
    private String expiryDate;
    private String verificationStatus;
    private String verifiedBy;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public KycDocument() {
    }

    public KycDocument(String documentId, String customerId, String documentType, String documentNumber, String issuingAuthority, String expiryDate, String verificationStatus, String verifiedBy) {
        this.documentId = documentId;
        this.customerId = customerId;
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.issuingAuthority = issuingAuthority;
        this.expiryDate = expiryDate;
        this.verificationStatus = verificationStatus;
        this.verifiedBy = verifiedBy;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.documentId = id;
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

    public synchronized void verifyDocument(String officer) {
        verificationStatus = "VERIFIED"; verifiedBy = officer;
        this.logStateChange("verifyDocument");
    }
    public synchronized void rejectDocument(String reason) {
        verificationStatus = "REJECTED"; issuingAuthority = reason;
        this.logStateChange("rejectDocument");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "KycDocument", String.valueOf(this.documentId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getDocumentId() {
        return this.documentId;
    }
    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }
    public String getCustomerId() {
        return this.customerId;
    }
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    public String getDocumentType() {
        return this.documentType;
    }
    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }
    public String getDocumentNumber() {
        return this.documentNumber;
    }
    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }
    public String getIssuingAuthority() {
        return this.issuingAuthority;
    }
    public void setIssuingAuthority(String issuingAuthority) {
        this.issuingAuthority = issuingAuthority;
    }
    public String getExpiryDate() {
        return this.expiryDate;
    }
    public void setExpiryDate(String expiryDate) {
        this.expiryDate = expiryDate;
    }
    public String getVerificationStatus() {
        return this.verificationStatus;
    }
    public void setVerificationStatus(String verificationStatus) {
        this.verificationStatus = verificationStatus;
    }
    public String getVerifiedBy() {
        return this.verifiedBy;
    }
    public void setVerifiedBy(String verifiedBy) {
        this.verifiedBy = verifiedBy;
    }
}
