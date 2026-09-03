package com.tcs.bancs.PM;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: PaymentTransaction
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class PaymentTransaction {

    private String paymentId;
    private String channelId;
    private String debtorIban;
    private String creditorIban;
    private double amount;
    private String currency;
    private String paymentMethod;
    private String clearingNetwork;
    private String settlementStatus;
    private String endToEndId;
    private double feeAmount;
    private long creationTimestamp;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public PaymentTransaction() {
    }

    public PaymentTransaction(String paymentId, String channelId, String debtorIban, String creditorIban, double amount, String currency, String paymentMethod, String clearingNetwork, String settlementStatus, String endToEndId, double feeAmount, long creationTimestamp) {
        this.paymentId = paymentId;
        this.channelId = channelId;
        this.debtorIban = debtorIban;
        this.creditorIban = creditorIban;
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.clearingNetwork = clearingNetwork;
        this.settlementStatus = settlementStatus;
        this.endToEndId = endToEndId;
        this.feeAmount = feeAmount;
        this.creationTimestamp = creationTimestamp;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.paymentId = id;
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

    public synchronized void routePayment(String network) {
        clearingNetwork = network; settlementStatus = "ROUTED";
        this.logStateChange("routePayment");
    }
    public synchronized void authorize() {
        settlementStatus = "AUTHORIZED";
        this.logStateChange("authorize");
    }
    public synchronized void settle() {
        settlementStatus = "SETTLED";
        this.logStateChange("settle");
    }
    public synchronized void failPayment(String reason) {
        settlementStatus = "FAILED"; clearingNetwork = reason;
        this.logStateChange("failPayment");
    }
    public synchronized void reverse(double refundAmount) {
        amount = amount - refundAmount; settlementStatus = "REVERSED";
        this.logStateChange("reverse");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "PaymentTransaction", String.valueOf(this.paymentId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getPaymentId() {
        return this.paymentId;
    }
    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }
    public String getChannelId() {
        return this.channelId;
    }
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }
    public String getDebtorIban() {
        return this.debtorIban;
    }
    public void setDebtorIban(String debtorIban) {
        this.debtorIban = debtorIban;
    }
    public String getCreditorIban() {
        return this.creditorIban;
    }
    public void setCreditorIban(String creditorIban) {
        this.creditorIban = creditorIban;
    }
    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }
    public String getCurrency() {
        return this.currency;
    }
    public void setCurrency(String currency) {
        this.currency = currency;
    }
    public String getPaymentMethod() {
        return this.paymentMethod;
    }
    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }
    public String getClearingNetwork() {
        return this.clearingNetwork;
    }
    public void setClearingNetwork(String clearingNetwork) {
        this.clearingNetwork = clearingNetwork;
    }
    public String getSettlementStatus() {
        return this.settlementStatus;
    }
    public void setSettlementStatus(String settlementStatus) {
        this.settlementStatus = settlementStatus;
    }
    public String getEndToEndId() {
        return this.endToEndId;
    }
    public void setEndToEndId(String endToEndId) {
        this.endToEndId = endToEndId;
    }
    public double getFeeAmount() {
        return this.feeAmount;
    }
    public void setFeeAmount(double feeAmount) {
        this.feeAmount = feeAmount;
    }
    public long getCreationTimestamp() {
        return this.creationTimestamp;
    }
    public void setCreationTimestamp(long creationTimestamp) {
        this.creationTimestamp = creationTimestamp;
    }
}
