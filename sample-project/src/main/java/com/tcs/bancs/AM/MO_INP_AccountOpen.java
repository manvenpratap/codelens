package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_AccountOpen
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_AccountOpen implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private String accountType;
    private String currency;
    private double initialDeposit;
    private String branchCode;
    private String messageCorrelationId;

    public MO_INP_AccountOpen() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_AccountOpen(String customerId, String accountType, String currency, double initialDeposit, String branchCode) {
        this();
        this.customerId = customerId;
        this.accountType = accountType;
        this.currency = currency;
        this.initialDeposit = initialDeposit;
        this.branchCode = branchCode;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
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
    public String getCurrency() {
        return this.currency;
    }
    public void setCurrency(String currency) {
        this.currency = currency;
    }
    public double getInitialDeposit() {
        return this.initialDeposit;
    }
    public void setInitialDeposit(double initialDeposit) {
        this.initialDeposit = initialDeposit;
    }
    public String getBranchCode() {
        return this.branchCode;
    }
    public void setBranchCode(String branchCode) {
        this.branchCode = branchCode;
    }

    @Override
    public String toString() {
        return "MO_INP_AccountOpen{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
