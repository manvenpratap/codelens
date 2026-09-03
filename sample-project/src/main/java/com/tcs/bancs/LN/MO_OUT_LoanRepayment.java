package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_LoanRepayment
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_LoanRepayment implements Serializable {

    private static final long serialVersionUID = 1L;

    private String receiptNumber;
    private String loanId;
    private double principalPaid;
    private double interestPaid;
    private double remainingBalance;
    private String messageCorrelationId;

    public MO_OUT_LoanRepayment() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_LoanRepayment(String receiptNumber, String loanId, double principalPaid, double interestPaid, double remainingBalance) {
        this();
        this.receiptNumber = receiptNumber;
        this.loanId = loanId;
        this.principalPaid = principalPaid;
        this.interestPaid = interestPaid;
        this.remainingBalance = remainingBalance;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getReceiptNumber() {
        return this.receiptNumber;
    }
    public void setReceiptNumber(String receiptNumber) {
        this.receiptNumber = receiptNumber;
    }
    public String getLoanId() {
        return this.loanId;
    }
    public void setLoanId(String loanId) {
        this.loanId = loanId;
    }
    public double getPrincipalPaid() {
        return this.principalPaid;
    }
    public void setPrincipalPaid(double principalPaid) {
        this.principalPaid = principalPaid;
    }
    public double getInterestPaid() {
        return this.interestPaid;
    }
    public void setInterestPaid(double interestPaid) {
        this.interestPaid = interestPaid;
    }
    public double getRemainingBalance() {
        return this.remainingBalance;
    }
    public void setRemainingBalance(double remainingBalance) {
        this.remainingBalance = remainingBalance;
    }

    @Override
    public String toString() {
        return "MO_OUT_LoanRepayment{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
