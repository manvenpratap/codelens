package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_AccountOpen
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_AccountOpen implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accountNumber;
    private String status;
    private String responseCode;
    private double initialBalance;
    private long openTimestamp;
    private String messageCorrelationId;

    public MO_OUT_AccountOpen() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_AccountOpen(String accountNumber, String status, String responseCode, double initialBalance, long openTimestamp) {
        this();
        this.accountNumber = accountNumber;
        this.status = status;
        this.responseCode = responseCode;
        this.initialBalance = initialBalance;
        this.openTimestamp = openTimestamp;
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
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public String getResponseCode() {
        return this.responseCode;
    }
    public void setResponseCode(String responseCode) {
        this.responseCode = responseCode;
    }
    public double getInitialBalance() {
        return this.initialBalance;
    }
    public void setInitialBalance(double initialBalance) {
        this.initialBalance = initialBalance;
    }
    public long getOpenTimestamp() {
        return this.openTimestamp;
    }
    public void setOpenTimestamp(long openTimestamp) {
        this.openTimestamp = openTimestamp;
    }

    @Override
    public String toString() {
        return "MO_OUT_AccountOpen{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
