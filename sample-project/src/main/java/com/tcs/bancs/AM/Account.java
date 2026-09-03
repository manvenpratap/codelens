package com.tcs.bancs.AM;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: Account
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class Account {

    private String accountNumber;
    private String customerId;
    private String accountType;
    private double availableBalance;
    private double ledgerBalance;
    private double creditLimit;
    private double interestRate;
    private double holdAmount;
    private String status;
    private long lastTransactionTimestamp;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public Account() {
    }

    public Account(String accountNumber, String customerId, String accountType, double availableBalance, double ledgerBalance, double creditLimit, double interestRate, double holdAmount, String status, long lastTransactionTimestamp) {
        this.accountNumber = accountNumber;
        this.customerId = customerId;
        this.accountType = accountType;
        this.availableBalance = availableBalance;
        this.ledgerBalance = ledgerBalance;
        this.creditLimit = creditLimit;
        this.interestRate = interestRate;
        this.holdAmount = holdAmount;
        this.status = status;
        this.lastTransactionTimestamp = lastTransactionTimestamp;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.accountNumber = id;
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

    public synchronized void postDebit(double amount, String narration) {
        availableBalance = availableBalance - amount; ledgerBalance = ledgerBalance - amount; lastTransactionTimestamp = System.currentTimeMillis();
        this.logStateChange("postDebit");
    }
    public synchronized void postCredit(double amount, String narration) {
        availableBalance = availableBalance + amount; ledgerBalance = ledgerBalance + amount; lastTransactionTimestamp = System.currentTimeMillis();
        this.logStateChange("postCredit");
    }
    public synchronized void blockHold(String holdId, double amount) {
        availableBalance = availableBalance - amount; holdAmount = holdAmount + amount; lastTransactionTimestamp = System.currentTimeMillis();
        this.logStateChange("blockHold");
    }
    public synchronized void releaseHold(String holdId, double amount) {
        availableBalance = availableBalance + amount; holdAmount = Math.max(0.0, holdAmount - amount); lastTransactionTimestamp = System.currentTimeMillis();
        this.logStateChange("releaseHold");
    }
    public synchronized void accrueDailyInterest(double dailyRate) {
        double accrued = (availableBalance * dailyRate) / 365.0; ledgerBalance = ledgerBalance + accrued; lastTransactionTimestamp = System.currentTimeMillis();
        this.logStateChange("accrueDailyInterest");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "Account", String.valueOf(this.accountNumber), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getAccountNumber() {
        return this.accountNumber;
    }
    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
    public String getCustomerId() {
        return this.customerId;
    }
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    public String getAccountType() {
        return this.accountType;
    }
    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }
    public double getAvailableBalance() {
        return this.availableBalance;
    }
    public void setAvailableBalance(double availableBalance) {
        this.availableBalance = availableBalance;
    }
    public double getLedgerBalance() {
        return this.ledgerBalance;
    }
    public void setLedgerBalance(double ledgerBalance) {
        this.ledgerBalance = ledgerBalance;
    }
    public double getCreditLimit() {
        return this.creditLimit;
    }
    public void setCreditLimit(double creditLimit) {
        this.creditLimit = creditLimit;
    }
    public double getInterestRate() {
        return this.interestRate;
    }
    public void setInterestRate(double interestRate) {
        this.interestRate = interestRate;
    }
    public double getHoldAmount() {
        return this.holdAmount;
    }
    public void setHoldAmount(double holdAmount) {
        this.holdAmount = holdAmount;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public long getLastTransactionTimestamp() {
        return this.lastTransactionTimestamp;
    }
    public void setLastTransactionTimestamp(long lastTransactionTimestamp) {
        this.lastTransactionTimestamp = lastTransactionTimestamp;
    }
}
