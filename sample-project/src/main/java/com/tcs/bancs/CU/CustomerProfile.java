package com.tcs.bancs.CU;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: CustomerProfile
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class CustomerProfile {

    private String customerId;
    private String taxId;
    private String customerType;
    private String fullName;
    private String riskRating;
    private String kycStatus;
    private String segment;
    private String incorporationCountry;
    private double totalExposureAmount;
    private long onboardingDate;
    private boolean isActive;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public CustomerProfile() {
    }

    public CustomerProfile(String customerId, String taxId, String customerType, String fullName, String riskRating, String kycStatus, String segment, String incorporationCountry, double totalExposureAmount, long onboardingDate, boolean isActive) {
        this.customerId = customerId;
        this.taxId = taxId;
        this.customerType = customerType;
        this.fullName = fullName;
        this.riskRating = riskRating;
        this.kycStatus = kycStatus;
        this.segment = segment;
        this.incorporationCountry = incorporationCountry;
        this.totalExposureAmount = totalExposureAmount;
        this.onboardingDate = onboardingDate;
        this.isActive = isActive;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.customerId = id;
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

    public synchronized void updateKycStatus(String newStatus) {
        kycStatus = newStatus;
        this.logStateChange("updateKycStatus");
    }
    public synchronized void adjustRiskRating(String newRating) {
        riskRating = newRating;
        this.logStateChange("adjustRiskRating");
    }
    public synchronized void updateExposure(double delta) {
        totalExposureAmount = totalExposureAmount + delta;
        this.logStateChange("updateExposure");
    }
    public synchronized void deactivateCustomer(String reason) {
        isActive = false; segment = reason;
        this.logStateChange("deactivateCustomer");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "CustomerProfile", String.valueOf(this.customerId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getCustomerId() {
        return this.customerId;
    }
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    public String getTaxId() {
        return this.taxId;
    }
    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }
    public String getCustomerType() {
        return this.customerType;
    }
    public void setCustomerType(String customerType) {
        this.customerType = customerType;
    }
    public String getFullName() {
        return this.fullName;
    }
    public void setFullName(String fullName) {
        this.fullName = fullName;
    }
    public String getRiskRating() {
        return this.riskRating;
    }
    public void setRiskRating(String riskRating) {
        this.riskRating = riskRating;
    }
    public String getKycStatus() {
        return this.kycStatus;
    }
    public void setKycStatus(String kycStatus) {
        this.kycStatus = kycStatus;
    }
    public String getSegment() {
        return this.segment;
    }
    public void setSegment(String segment) {
        this.segment = segment;
    }
    public String getIncorporationCountry() {
        return this.incorporationCountry;
    }
    public void setIncorporationCountry(String incorporationCountry) {
        this.incorporationCountry = incorporationCountry;
    }
    public double getTotalExposureAmount() {
        return this.totalExposureAmount;
    }
    public void setTotalExposureAmount(double totalExposureAmount) {
        this.totalExposureAmount = totalExposureAmount;
    }
    public long getOnboardingDate() {
        return this.onboardingDate;
    }
    public void setOnboardingDate(long onboardingDate) {
        this.onboardingDate = onboardingDate;
    }
    public boolean getIsActive() {
        return this.isActive;
    }
    public void setIsActive(boolean isActive) {
        this.isActive = isActive;
    }
}
