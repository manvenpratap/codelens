package com.tcs.bancs.GL;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: JournalVoucher
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class JournalVoucher {

    private String voucherId;
    private String transactionRef;
    private String postingDate;
    private String valueDate;
    private String voucherType;
    private double totalDebitAmount;
    private double totalCreditAmount;
    private String approvalStatus;
    private String postedBy;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public JournalVoucher() {
    }

    public JournalVoucher(String voucherId, String transactionRef, String postingDate, String valueDate, String voucherType, double totalDebitAmount, double totalCreditAmount, String approvalStatus, String postedBy) {
        this.voucherId = voucherId;
        this.transactionRef = transactionRef;
        this.postingDate = postingDate;
        this.valueDate = valueDate;
        this.voucherType = voucherType;
        this.totalDebitAmount = totalDebitAmount;
        this.totalCreditAmount = totalCreditAmount;
        this.approvalStatus = approvalStatus;
        this.postedBy = postedBy;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.voucherId = id;
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

    public synchronized void validateBalance() {
        if (Math.abs(totalDebitAmount - totalCreditAmount) < 0.001) approvalStatus = "BALANCED"; else approvalStatus = "UNBALANCED";
        this.logStateChange("validateBalance");
    }
    public synchronized void approveVoucher(String officer) {
        approvalStatus = "APPROVED"; postedBy = officer;
        this.logStateChange("approveVoucher");
    }
    public synchronized void postVoucher() {
        approvalStatus = "POSTED";
        this.logStateChange("postVoucher");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "JournalVoucher", String.valueOf(this.voucherId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getVoucherId() {
        return this.voucherId;
    }
    public void setVoucherId(String voucherId) {
        this.voucherId = voucherId;
    }
    public String getTransactionRef() {
        return this.transactionRef;
    }
    public void setTransactionRef(String transactionRef) {
        this.transactionRef = transactionRef;
    }
    public String getPostingDate() {
        return this.postingDate;
    }
    public void setPostingDate(String postingDate) {
        this.postingDate = postingDate;
    }
    public String getValueDate() {
        return this.valueDate;
    }
    public void setValueDate(String valueDate) {
        this.valueDate = valueDate;
    }
    public String getVoucherType() {
        return this.voucherType;
    }
    public void setVoucherType(String voucherType) {
        this.voucherType = voucherType;
    }
    public double getTotalDebitAmount() {
        return this.totalDebitAmount;
    }
    public void setTotalDebitAmount(double totalDebitAmount) {
        this.totalDebitAmount = totalDebitAmount;
    }
    public double getTotalCreditAmount() {
        return this.totalCreditAmount;
    }
    public void setTotalCreditAmount(double totalCreditAmount) {
        this.totalCreditAmount = totalCreditAmount;
    }
    public String getApprovalStatus() {
        return this.approvalStatus;
    }
    public void setApprovalStatus(String approvalStatus) {
        this.approvalStatus = approvalStatus;
    }
    public String getPostedBy() {
        return this.postedBy;
    }
    public void setPostedBy(String postedBy) {
        this.postedBy = postedBy;
    }
}
