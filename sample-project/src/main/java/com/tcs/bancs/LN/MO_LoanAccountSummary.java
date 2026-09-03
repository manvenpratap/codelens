package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_LoanAccountSummary
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_LoanAccountSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    private String loanId;
    private String customerId;
    private double outstandingBalance;
    private String npaCategory;
    private String status;
    private String messageCorrelationId;

    public MO_LoanAccountSummary() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_LoanAccountSummary(String loanId, String customerId, double outstandingBalance, String npaCategory, String status) {
        this();
        this.loanId = loanId;
        this.customerId = customerId;
        this.outstandingBalance = outstandingBalance;
        this.npaCategory = npaCategory;
        this.status = status;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
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
    public double getOutstandingBalance() {
        return this.outstandingBalance;
    }
    public void setOutstandingBalance(double outstandingBalance) {
        this.outstandingBalance = outstandingBalance;
    }
    public String getNpaCategory() {
        return this.npaCategory;
    }
    public void setNpaCategory(String npaCategory) {
        this.npaCategory = npaCategory;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MO_LoanAccountSummary{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
