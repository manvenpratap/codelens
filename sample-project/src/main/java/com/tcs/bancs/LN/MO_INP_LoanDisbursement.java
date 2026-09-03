package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_LoanDisbursement
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_LoanDisbursement implements Serializable {

    private static final long serialVersionUID = 1L;

    private String loanId;
    private double disbursementAmount;
    private String creditAccountNumber;
    private String trancheNumber;
    private String messageCorrelationId;

    public MO_INP_LoanDisbursement() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_LoanDisbursement(String loanId, double disbursementAmount, String creditAccountNumber, String trancheNumber) {
        this();
        this.loanId = loanId;
        this.disbursementAmount = disbursementAmount;
        this.creditAccountNumber = creditAccountNumber;
        this.trancheNumber = trancheNumber;
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
    public double getDisbursementAmount() {
        return this.disbursementAmount;
    }
    public void setDisbursementAmount(double disbursementAmount) {
        this.disbursementAmount = disbursementAmount;
    }
    public String getCreditAccountNumber() {
        return this.creditAccountNumber;
    }
    public void setCreditAccountNumber(String creditAccountNumber) {
        this.creditAccountNumber = creditAccountNumber;
    }
    public String getTrancheNumber() {
        return this.trancheNumber;
    }
    public void setTrancheNumber(String trancheNumber) {
        this.trancheNumber = trancheNumber;
    }

    @Override
    public String toString() {
        return "MO_INP_LoanDisbursement{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
