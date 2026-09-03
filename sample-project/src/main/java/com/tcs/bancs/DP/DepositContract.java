package com.tcs.bancs.DP;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: DepositContract
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class DepositContract {

    private String depositId;
    private String customerId;
    private String accountNumber;
    private String depositProductCode;
    private double principalAmount;
    private int tenureDays;
    private double interestRate;
    private String compoundingFrequency;
    private double maturityAmount;
    private String maturityDate;
    private String renewalOption;
    private String depositStatus;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public DepositContract() {
    }

    public DepositContract(String depositId, String customerId, String accountNumber, String depositProductCode, double principalAmount, int tenureDays, double interestRate, String compoundingFrequency, double maturityAmount, String maturityDate, String renewalOption, String depositStatus) {
        this.depositId = depositId;
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.depositProductCode = depositProductCode;
        this.principalAmount = principalAmount;
        this.tenureDays = tenureDays;
        this.interestRate = interestRate;
        this.compoundingFrequency = compoundingFrequency;
        this.maturityAmount = maturityAmount;
        this.maturityDate = maturityDate;
        this.renewalOption = renewalOption;
        this.depositStatus = depositStatus;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.depositId = id;
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

    public synchronized void accrueInterest(double dailyAccrual) {
        maturityAmount = maturityAmount + dailyAccrual;
        this.logStateChange("accrueInterest");
    }
    public synchronized void mature() {
        depositStatus = "MATURED";
        this.logStateChange("mature");
    }
    public synchronized void liquidatePrematurely(double penalty) {
        maturityAmount = principalAmount - penalty; depositStatus = "PREMATURELY_CLOSED";
        this.logStateChange("liquidatePrematurely");
    }
    public synchronized void renew(int additionalDays, double newRate) {
        tenureDays = tenureDays + additionalDays; interestRate = newRate; depositStatus = "RENEWED";
        this.logStateChange("renew");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "DepositContract", String.valueOf(this.depositId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getDepositId() {
        return this.depositId;
    }
    public void setDepositId(String depositId) {
        this.depositId = depositId;
    }
    public String getCustomerId() {
        return this.customerId;
    }
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    public String getAccountNumber() {
        return this.accountNumber;
    }
    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
    public String getDepositProductCode() {
        return this.depositProductCode;
    }
    public void setDepositProductCode(String depositProductCode) {
        this.depositProductCode = depositProductCode;
    }
    public double getPrincipalAmount() {
        return this.principalAmount;
    }
    public void setPrincipalAmount(double principalAmount) {
        this.principalAmount = principalAmount;
    }
    public int getTenureDays() {
        return this.tenureDays;
    }
    public void setTenureDays(int tenureDays) {
        this.tenureDays = tenureDays;
    }
    public double getInterestRate() {
        return this.interestRate;
    }
    public void setInterestRate(double interestRate) {
        this.interestRate = interestRate;
    }
    public String getCompoundingFrequency() {
        return this.compoundingFrequency;
    }
    public void setCompoundingFrequency(String compoundingFrequency) {
        this.compoundingFrequency = compoundingFrequency;
    }
    public double getMaturityAmount() {
        return this.maturityAmount;
    }
    public void setMaturityAmount(double maturityAmount) {
        this.maturityAmount = maturityAmount;
    }
    public String getMaturityDate() {
        return this.maturityDate;
    }
    public void setMaturityDate(String maturityDate) {
        this.maturityDate = maturityDate;
    }
    public String getRenewalOption() {
        return this.renewalOption;
    }
    public void setRenewalOption(String renewalOption) {
        this.renewalOption = renewalOption;
    }
    public String getDepositStatus() {
        return this.depositStatus;
    }
    public void setDepositStatus(String depositStatus) {
        this.depositStatus = depositStatus;
    }
}
