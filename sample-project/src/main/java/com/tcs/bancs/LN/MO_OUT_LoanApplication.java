package com.tcs.bancs.LN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_LoanApplication
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_LoanApplication implements Serializable {

    private static final long serialVersionUID = 1L;

    private String loanId;
    private String status;
    private double approvedAmount;
    private double assignedRate;
    private double estimatedEmi;
    private String messageCorrelationId;

    public MO_OUT_LoanApplication() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_LoanApplication(String loanId, String status, double approvedAmount, double assignedRate, double estimatedEmi) {
        this();
        this.loanId = loanId;
        this.status = status;
        this.approvedAmount = approvedAmount;
        this.assignedRate = assignedRate;
        this.estimatedEmi = estimatedEmi;
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
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public double getApprovedAmount() {
        return this.approvedAmount;
    }
    public void setApprovedAmount(double approvedAmount) {
        this.approvedAmount = approvedAmount;
    }
    public double getAssignedRate() {
        return this.assignedRate;
    }
    public void setAssignedRate(double assignedRate) {
        this.assignedRate = assignedRate;
    }
    public double getEstimatedEmi() {
        return this.estimatedEmi;
    }
    public void setEstimatedEmi(double estimatedEmi) {
        this.estimatedEmi = estimatedEmi;
    }

    @Override
    public String toString() {
        return "MO_OUT_LoanApplication{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
