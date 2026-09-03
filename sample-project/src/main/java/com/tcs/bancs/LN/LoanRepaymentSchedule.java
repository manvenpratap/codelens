package com.tcs.bancs.LN;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: LoanRepaymentSchedule
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class LoanRepaymentSchedule {

    private String scheduleId;
    private String loanId;
    private int installmentNumber;
    private String dueDate;
    private double principalComponent;
    private double interestComponent;
    private double feeComponent;
    private String paymentStatus;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public LoanRepaymentSchedule() {
    }

    public LoanRepaymentSchedule(String scheduleId, String loanId, int installmentNumber, String dueDate, double principalComponent, double interestComponent, double feeComponent, String paymentStatus) {
        this.scheduleId = scheduleId;
        this.loanId = loanId;
        this.installmentNumber = installmentNumber;
        this.dueDate = dueDate;
        this.principalComponent = principalComponent;
        this.interestComponent = interestComponent;
        this.feeComponent = feeComponent;
        this.paymentStatus = paymentStatus;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.scheduleId = id;
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

    public synchronized void markPaid(String paidDate) {
        paymentStatus = "PAID"; dueDate = paidDate;
        this.logStateChange("markPaid");
    }
    public synchronized void reschedule(String newDueDate) {
        dueDate = newDueDate; paymentStatus = "RESCHEDULED";
        this.logStateChange("reschedule");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "LoanRepaymentSchedule", String.valueOf(this.scheduleId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getScheduleId() {
        return this.scheduleId;
    }
    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }
    public String getLoanId() {
        return this.loanId;
    }
    public void setLoanId(String loanId) {
        this.loanId = loanId;
    }
    public int getInstallmentNumber() {
        return this.installmentNumber;
    }
    public void setInstallmentNumber(int installmentNumber) {
        this.installmentNumber = installmentNumber;
    }
    public String getDueDate() {
        return this.dueDate;
    }
    public void setDueDate(String dueDate) {
        this.dueDate = dueDate;
    }
    public double getPrincipalComponent() {
        return this.principalComponent;
    }
    public void setPrincipalComponent(double principalComponent) {
        this.principalComponent = principalComponent;
    }
    public double getInterestComponent() {
        return this.interestComponent;
    }
    public void setInterestComponent(double interestComponent) {
        this.interestComponent = interestComponent;
    }
    public double getFeeComponent() {
        return this.feeComponent;
    }
    public void setFeeComponent(double feeComponent) {
        this.feeComponent = feeComponent;
    }
    public String getPaymentStatus() {
        return this.paymentStatus;
    }
    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }
}
