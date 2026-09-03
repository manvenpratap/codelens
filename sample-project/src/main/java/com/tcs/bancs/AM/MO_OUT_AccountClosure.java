package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_AccountClosure
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_AccountClosure implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accountNumber;
    private String status;
    private double finalSettlementAmount;
    private long closureTimestamp;
    private String messageCorrelationId;

    public MO_OUT_AccountClosure() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_AccountClosure(String accountNumber, String status, double finalSettlementAmount, long closureTimestamp) {
        this();
        this.accountNumber = accountNumber;
        this.status = status;
        this.finalSettlementAmount = finalSettlementAmount;
        this.closureTimestamp = closureTimestamp;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getAccountNumber() {
        return this.accountNumber;
    }
    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public double getFinalSettlementAmount() {
        return this.finalSettlementAmount;
    }
    public void setFinalSettlementAmount(double finalSettlementAmount) {
        this.finalSettlementAmount = finalSettlementAmount;
    }
    public long getClosureTimestamp() {
        return this.closureTimestamp;
    }
    public void setClosureTimestamp(long closureTimestamp) {
        this.closureTimestamp = closureTimestamp;
    }

    @Override
    public String toString() {
        return "MO_OUT_AccountClosure{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
