package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_HoldFunds
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_HoldFunds implements Serializable {

    private static final long serialVersionUID = 1L;

    private String holdReferenceId;
    private String accountNumber;
    private double amountHeld;
    private String status;
    private String messageCorrelationId;

    public MO_OUT_HoldFunds() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_HoldFunds(String holdReferenceId, String accountNumber, double amountHeld, String status) {
        this();
        this.holdReferenceId = holdReferenceId;
        this.accountNumber = accountNumber;
        this.amountHeld = amountHeld;
        this.status = status;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getHoldReferenceId() {
        return this.holdReferenceId;
    }
    public void setHoldReferenceId(String holdReferenceId) {
        this.holdReferenceId = holdReferenceId;
    }
    public String getAccountNumber() {
        return this.accountNumber;
    }
    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
    public double getAmountHeld() {
        return this.amountHeld;
    }
    public void setAmountHeld(double amountHeld) {
        this.amountHeld = amountHeld;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MO_OUT_HoldFunds{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
