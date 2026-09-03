package com.tcs.bancs.LN;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: Loan
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class Loan {

    private String loanId;
    private String customerId;
    private String loanType;
    private double sanctionedPrincipal;
    private double disbursedAmount;
    private double outstandingBalance;
    private double interestRate;
    private int tenureMonths;
    private double emiAmount;
    private String loanStatus;
    private String npaCategory;
    private String nextPaymentDueDate;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public Loan() {
    }

    public Loan(String loanId, String customerId, String loanType, double sanctionedPrincipal, double disbursedAmount, double outstandingBalance, double interestRate, int tenureMonths, double emiAmount, String loanStatus, String npaCategory, String nextPaymentDueDate) {
        this.loanId = loanId;
        this.customerId = customerId;
        this.loanType = loanType;
        this.sanctionedPrincipal = sanctionedPrincipal;
        this.disbursedAmount = disbursedAmount;
        this.outstandingBalance = outstandingBalance;
        this.interestRate = interestRate;
        this.tenureMonths = tenureMonths;
        this.emiAmount = emiAmount;
        this.loanStatus = loanStatus;
        this.npaCategory = npaCategory;
        this.nextPaymentDueDate = nextPaymentDueDate;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.loanId = id;
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

    public synchronized void disburse(double amount) {
        disbursedAmount = disbursedAmount + amount; outstandingBalance = outstandingBalance + amount; loanStatus = "ACTIVE";
        this.logStateChange("disburse");
    }
    public synchronized void applyRepayment(double principalPart, double interestPart) {
        outstandingBalance = Math.max(0.0, outstandingBalance - principalPart);
        this.logStateChange("applyRepayment");
    }
    public synchronized void recalculateEmi(double newRate, int remainingTenure) {
        interestRate = newRate; tenureMonths = remainingTenure; emiAmount = (outstandingBalance * (1 + newRate/100.0)) / Math.max(1, remainingTenure);
        this.logStateChange("recalculateEmi");
    }
    public synchronized void markDelinquent(String category) {
        npaCategory = category; loanStatus = "DELINQUENT";
        this.logStateChange("markDelinquent");
    }
    public synchronized void restructure(int extraTenure, double concessionalRate) {
        tenureMonths = tenureMonths + extraTenure; interestRate = concessionalRate;
        this.logStateChange("restructure");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "Loan", String.valueOf(this.loanId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getLoanId() {
        return this.loanId;
    }
    public void setLoanId(String loanId) {
        this.loanId = loanId;
    }
    public String getCustomerId() {
        return this.customerId;
    }
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    public String getLoanType() {
        return this.loanType;
    }
    public void setLoanType(String loanType) {
        this.loanType = loanType;
    }
    public double getSanctionedPrincipal() {
        return this.sanctionedPrincipal;
    }
    public void setSanctionedPrincipal(double sanctionedPrincipal) {
        this.sanctionedPrincipal = sanctionedPrincipal;
    }
    public double getDisbursedAmount() {
        return this.disbursedAmount;
    }
    public void setDisbursedAmount(double disbursedAmount) {
        this.disbursedAmount = disbursedAmount;
    }
    public double getOutstandingBalance() {
        return this.outstandingBalance;
    }
    public void setOutstandingBalance(double outstandingBalance) {
        this.outstandingBalance = outstandingBalance;
    }
    public double getInterestRate() {
        return this.interestRate;
    }
    public void setInterestRate(double interestRate) {
        this.interestRate = interestRate;
    }
    public int getTenureMonths() {
        return this.tenureMonths;
    }
    public void setTenureMonths(int tenureMonths) {
        this.tenureMonths = tenureMonths;
    }
    public double getEmiAmount() {
        return this.emiAmount;
    }
    public void setEmiAmount(double emiAmount) {
        this.emiAmount = emiAmount;
    }
    public String getLoanStatus() {
        return this.loanStatus;
    }
    public void setLoanStatus(String loanStatus) {
        this.loanStatus = loanStatus;
    }
    public String getNpaCategory() {
        return this.npaCategory;
    }
    public void setNpaCategory(String npaCategory) {
        this.npaCategory = npaCategory;
    }
    public String getNextPaymentDueDate() {
        return this.nextPaymentDueDate;
    }
    public void setNextPaymentDueDate(String nextPaymentDueDate) {
        this.nextPaymentDueDate = nextPaymentDueDate;
    }
}
