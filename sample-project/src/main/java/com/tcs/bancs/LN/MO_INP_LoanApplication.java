package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_LoanApplication
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_LoanApplication implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private String loanType;
    private double requestedAmount;
    private int tenureMonths;
    private String purpose;
    private String messageCorrelationId;

    public MO_INP_LoanApplication() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_LoanApplication(String customerId, String loanType, double requestedAmount, int tenureMonths, String purpose) {
        this();
        this.customerId = customerId;
        this.loanType = loanType;
        this.requestedAmount = requestedAmount;
        this.tenureMonths = tenureMonths;
        this.purpose = purpose;
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
    public String getLoanType() {
        return this.loanType;
    }
    public void setLoanType(String loanType) {
        this.loanType = loanType;
    }
    public double getRequestedAmount() {
        return this.requestedAmount;
    }
    public void setRequestedAmount(double requestedAmount) {
        this.requestedAmount = requestedAmount;
    }
    public int getTenureMonths() {
        return this.tenureMonths;
    }
    public void setTenureMonths(int tenureMonths) {
        this.tenureMonths = tenureMonths;
    }
    public String getPurpose() {
        return this.purpose;
    }
    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    @Override
    public String toString() {
        return "MO_INP_LoanApplication{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
