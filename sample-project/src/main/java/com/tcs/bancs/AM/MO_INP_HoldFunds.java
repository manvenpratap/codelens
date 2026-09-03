package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_HoldFunds
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_HoldFunds implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accountNumber;
    private double amount;
    private String reason;
    private String expiryDate;
    private String messageCorrelationId;

    public MO_INP_HoldFunds() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_HoldFunds(String accountNumber, double amount, String reason, String expiryDate) {
        this();
        this.accountNumber = accountNumber;
        this.amount = amount;
        this.reason = reason;
        this.expiryDate = expiryDate;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getAccountNumber() {
        return this.accountNumber;
    }
    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }
    public String getReason() {
        return this.reason;
    }
    public void setReason(String reason) {
        this.reason = reason;
    }
    public String getExpiryDate() {
        return this.expiryDate;
    }
    public void setExpiryDate(String expiryDate) {
        this.expiryDate = expiryDate;
    }

    @Override
    public String toString() {
        return "MO_INP_HoldFunds{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
