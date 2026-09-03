package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_LoanDisbursement
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_LoanDisbursement implements Serializable {

    private static final long serialVersionUID = 1L;

    private String disbursementRef;
    private String loanId;
    private String status;
    private double amountDisbursed;
    private long timestamp;
    private String messageCorrelationId;

    public MO_OUT_LoanDisbursement() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_LoanDisbursement(String disbursementRef, String loanId, String status, double amountDisbursed, long timestamp) {
        this();
        this.disbursementRef = disbursementRef;
        this.loanId = loanId;
        this.status = status;
        this.amountDisbursed = amountDisbursed;
        this.timestamp = timestamp;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getDisbursementRef() {
        return this.disbursementRef;
    }
    public void setDisbursementRef(String disbursementRef) {
        this.disbursementRef = disbursementRef;
    }
    public String getLoanId() {
        return this.loanId;
    }
    public void setLoanId(String loanId) {
        this.loanId = loanId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public double getAmountDisbursed() {
        return this.amountDisbursed;
    }
    public void setAmountDisbursed(double amountDisbursed) {
        this.amountDisbursed = amountDisbursed;
    }
    public long getTimestamp() {
        return this.timestamp;
    }
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "MO_OUT_LoanDisbursement{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
