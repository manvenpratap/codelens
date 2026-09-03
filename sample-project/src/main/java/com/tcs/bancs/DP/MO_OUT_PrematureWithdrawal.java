package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_PrematureWithdrawal
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_PrematureWithdrawal implements Serializable {

    private static final long serialVersionUID = 1L;

    private String depositId;
    private double refundAmount;
    private double penaltyDeducted;
    private String status;
    private String messageCorrelationId;

    public MO_OUT_PrematureWithdrawal() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_PrematureWithdrawal(String depositId, double refundAmount, double penaltyDeducted, String status) {
        this();
        this.depositId = depositId;
        this.refundAmount = refundAmount;
        this.penaltyDeducted = penaltyDeducted;
        this.status = status;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getDepositId() {
        return this.depositId;
    }
    public void setDepositId(String depositId) {
        this.depositId = depositId;
    }
    public double getRefundAmount() {
        return this.refundAmount;
    }
    public void setRefundAmount(double refundAmount) {
        this.refundAmount = refundAmount;
    }
    public double getPenaltyDeducted() {
        return this.penaltyDeducted;
    }
    public void setPenaltyDeducted(double penaltyDeducted) {
        this.penaltyDeducted = penaltyDeducted;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MO_OUT_PrematureWithdrawal{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
