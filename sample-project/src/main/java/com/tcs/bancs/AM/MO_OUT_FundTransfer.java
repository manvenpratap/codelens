package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_FundTransfer
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_FundTransfer implements Serializable {

    private static final long serialVersionUID = 1L;

    private String referenceId;
    private String status;
    private double sourceNewBalance;
    private double feeCharged;
    private long transferTime;
    private String messageCorrelationId;

    public MO_OUT_FundTransfer() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_FundTransfer(String referenceId, String status, double sourceNewBalance, double feeCharged, long transferTime) {
        this();
        this.referenceId = referenceId;
        this.status = status;
        this.sourceNewBalance = sourceNewBalance;
        this.feeCharged = feeCharged;
        this.transferTime = transferTime;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getReferenceId() {
        return this.referenceId;
    }
    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public double getSourceNewBalance() {
        return this.sourceNewBalance;
    }
    public void setSourceNewBalance(double sourceNewBalance) {
        this.sourceNewBalance = sourceNewBalance;
    }
    public double getFeeCharged() {
        return this.feeCharged;
    }
    public void setFeeCharged(double feeCharged) {
        this.feeCharged = feeCharged;
    }
    public long getTransferTime() {
        return this.transferTime;
    }
    public void setTransferTime(long transferTime) {
        this.transferTime = transferTime;
    }

    @Override
    public String toString() {
        return "MO_OUT_FundTransfer{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
