package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_BalanceInquiry
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_BalanceInquiry implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accountNumber;
    private double availableBalance;
    private double ledgerBalance;
    private double holdAmount;
    private String status;
    private String messageCorrelationId;

    public MO_OUT_BalanceInquiry() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_BalanceInquiry(String accountNumber, double availableBalance, double ledgerBalance, double holdAmount, String status) {
        this();
        this.accountNumber = accountNumber;
        this.availableBalance = availableBalance;
        this.ledgerBalance = ledgerBalance;
        this.holdAmount = holdAmount;
        this.status = status;
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
    public double getAvailableBalance() {
        return this.availableBalance;
    }
    public void setAvailableBalance(double availableBalance) {
        this.availableBalance = availableBalance;
    }
    public double getLedgerBalance() {
        return this.ledgerBalance;
    }
    public void setLedgerBalance(double ledgerBalance) {
        this.ledgerBalance = ledgerBalance;
    }
    public double getHoldAmount() {
        return this.holdAmount;
    }
    public void setHoldAmount(double holdAmount) {
        this.holdAmount = holdAmount;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MO_OUT_BalanceInquiry{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
