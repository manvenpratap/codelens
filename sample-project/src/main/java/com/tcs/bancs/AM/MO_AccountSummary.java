package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_AccountSummary
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_AccountSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accountNumber;
    private String customerId;
    private String accountType;
    private double balance;
    private String status;
    private String messageCorrelationId;

    public MO_AccountSummary() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_AccountSummary(String accountNumber, String customerId, String accountType, double balance, String status) {
        this();
        this.accountNumber = accountNumber;
        this.customerId = customerId;
        this.accountType = accountType;
        this.balance = balance;
        this.status = status;
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
    public double getBalance() {
        return this.balance;
    }
    public void setBalance(double balance) {
        this.balance = balance;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MO_AccountSummary{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
