package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_FundTransfer
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_FundTransfer implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sourceAccount;
    private String destinationAccount;
    private double amount;
    private String currency;
    private String narration;
    private String messageCorrelationId;

    public MO_INP_FundTransfer() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_FundTransfer(String sourceAccount, String destinationAccount, double amount, String currency, String narration) {
        this();
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.amount = amount;
        this.currency = currency;
        this.narration = narration;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getSourceAccount() {
        return this.sourceAccount;
    }
    public void setSourceAccount(String sourceAccount) {
        this.sourceAccount = sourceAccount;
    }
    public String getDestinationAccount() {
        return this.destinationAccount;
    }
    public void setDestinationAccount(String destinationAccount) {
        this.destinationAccount = destinationAccount;
    }
    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }
    public String getCurrency() {
        return this.currency;
    }
    public void setCurrency(String currency) {
        this.currency = currency;
    }
    public String getNarration() {
        return this.narration;
    }
    public void setNarration(String narration) {
        this.narration = narration;
    }

    @Override
    public String toString() {
        return "MO_INP_FundTransfer{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
