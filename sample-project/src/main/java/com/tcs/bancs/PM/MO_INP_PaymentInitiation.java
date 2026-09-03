package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_PaymentInitiation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_PaymentInitiation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String debtorAccount;
    private String creditorIban;
    private double amount;
    private String currency;
    private String method;
    private String narrative;
    private String messageCorrelationId;

    public MO_INP_PaymentInitiation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_PaymentInitiation(String debtorAccount, String creditorIban, double amount, String currency, String method, String narrative) {
        this();
        this.debtorAccount = debtorAccount;
        this.creditorIban = creditorIban;
        this.amount = amount;
        this.currency = currency;
        this.method = method;
        this.narrative = narrative;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getDebtorAccount() {
        return this.debtorAccount;
    }
    public void setDebtorAccount(String debtorAccount) {
        this.debtorAccount = debtorAccount;
    }
    public String getCreditorIban() {
        return this.creditorIban;
    }
    public void setCreditorIban(String creditorIban) {
        this.creditorIban = creditorIban;
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
    public String getMethod() {
        return this.method;
    }
    public void setMethod(String method) {
        this.method = method;
    }
    public String getNarrative() {
        return this.narrative;
    }
    public void setNarrative(String narrative) {
        this.narrative = narrative;
    }

    @Override
    public String toString() {
        return "MO_INP_PaymentInitiation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
