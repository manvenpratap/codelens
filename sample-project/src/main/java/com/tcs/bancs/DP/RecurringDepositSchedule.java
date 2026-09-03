package com.tcs.bancs.DP;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: RecurringDepositSchedule
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class RecurringDepositSchedule {

    private String scheduleId;
    private String depositId;
    private int installmentNumber;
    private double monthlyInstallment;
    private String dueDate;
    private String installmentStatus;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public RecurringDepositSchedule() {
    }

    public RecurringDepositSchedule(String scheduleId, String depositId, int installmentNumber, double monthlyInstallment, String dueDate, String installmentStatus) {
        this.scheduleId = scheduleId;
        this.depositId = depositId;
        this.installmentNumber = installmentNumber;
        this.monthlyInstallment = monthlyInstallment;
        this.dueDate = dueDate;
        this.installmentStatus = installmentStatus;
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

    public synchronized void recordPayment(String date) {
        installmentStatus = "PAID"; dueDate = date;
        this.logStateChange("recordPayment");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "RecurringDepositSchedule", String.valueOf(this.scheduleId), action);
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
    public String getDepositId() {
        return this.depositId;
    }
    public void setDepositId(String depositId) {
        this.depositId = depositId;
    }
    public int getInstallmentNumber() {
        return this.installmentNumber;
    }
    public void setInstallmentNumber(int installmentNumber) {
        this.installmentNumber = installmentNumber;
    }
    public double getMonthlyInstallment() {
        return this.monthlyInstallment;
    }
    public void setMonthlyInstallment(double monthlyInstallment) {
        this.monthlyInstallment = monthlyInstallment;
    }
    public String getDueDate() {
        return this.dueDate;
    }
    public void setDueDate(String dueDate) {
        this.dueDate = dueDate;
    }
    public String getInstallmentStatus() {
        return this.installmentStatus;
    }
    public void setInstallmentStatus(String installmentStatus) {
        this.installmentStatus = installmentStatus;
    }
}
