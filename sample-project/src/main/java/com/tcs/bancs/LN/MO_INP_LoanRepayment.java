package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_LoanRepayment
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_LoanRepayment implements Serializable {

    private static final long serialVersionUID = 1L;

    private String loanId;
    private double paymentAmount;
    private String debitAccountNumber;
    private String paymentMode;
    private String messageCorrelationId;

    public MO_INP_LoanRepayment() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_LoanRepayment(String loanId, double paymentAmount, String debitAccountNumber, String paymentMode) {
        this();
        this.loanId = loanId;
        this.paymentAmount = paymentAmount;
        this.debitAccountNumber = debitAccountNumber;
        this.paymentMode = paymentMode;
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
    public double getPaymentAmount() {
        return this.paymentAmount;
    }
    public void setPaymentAmount(double paymentAmount) {
        this.paymentAmount = paymentAmount;
    }
    public String getDebitAccountNumber() {
        return this.debitAccountNumber;
    }
    public void setDebitAccountNumber(String debitAccountNumber) {
        this.debitAccountNumber = debitAccountNumber;
    }
    public String getPaymentMode() {
        return this.paymentMode;
    }
    public void setPaymentMode(String paymentMode) {
        this.paymentMode = paymentMode;
    }

    @Override
    public String toString() {
        return "MO_INP_LoanRepayment{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
