package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_AmortizationInstallment
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_AmortizationInstallment implements Serializable {

    private static final long serialVersionUID = 1L;

    private int periodNumber;
    private String dueDate;
    private double installmentAmount;
    private double principal;
    private double interest;
    private String messageCorrelationId;

    public MO_AmortizationInstallment() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_AmortizationInstallment(int periodNumber, String dueDate, double installmentAmount, double principal, double interest) {
        this();
        this.periodNumber = periodNumber;
        this.dueDate = dueDate;
        this.installmentAmount = installmentAmount;
        this.principal = principal;
        this.interest = interest;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public int getPeriodNumber() {
        return this.periodNumber;
    }
    public void setPeriodNumber(int periodNumber) {
        this.periodNumber = periodNumber;
    }
    public String getDueDate() {
        return this.dueDate;
    }
    public void setDueDate(String dueDate) {
        this.dueDate = dueDate;
    }
    public double getInstallmentAmount() {
        return this.installmentAmount;
    }
    public void setInstallmentAmount(double installmentAmount) {
        this.installmentAmount = installmentAmount;
    }
    public double getPrincipal() {
        return this.principal;
    }
    public void setPrincipal(double principal) {
        this.principal = principal;
    }
    public double getInterest() {
        return this.interest;
    }
    public void setInterest(double interest) {
        this.interest = interest;
    }

    @Override
    public String toString() {
        return "MO_AmortizationInstallment{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
