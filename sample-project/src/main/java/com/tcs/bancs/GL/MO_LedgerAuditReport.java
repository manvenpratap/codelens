package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_LedgerAuditReport
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_LedgerAuditReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private String glCode;
    private double openingBalance;
    private double closingBalance;
    private int txnCount;
    private String messageCorrelationId;

    public MO_LedgerAuditReport() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_LedgerAuditReport(String glCode, double openingBalance, double closingBalance, int txnCount) {
        this();
        this.glCode = glCode;
        this.openingBalance = openingBalance;
        this.closingBalance = closingBalance;
        this.txnCount = txnCount;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getGlCode() {
        return this.glCode;
    }
    public void setGlCode(String glCode) {
        this.glCode = glCode;
    }
    public double getOpeningBalance() {
        return this.openingBalance;
    }
    public void setOpeningBalance(double openingBalance) {
        this.openingBalance = openingBalance;
    }
    public double getClosingBalance() {
        return this.closingBalance;
    }
    public void setClosingBalance(double closingBalance) {
        this.closingBalance = closingBalance;
    }
    public int getTxnCount() {
        return this.txnCount;
    }
    public void setTxnCount(int txnCount) {
        this.txnCount = txnCount;
    }

    @Override
    public String toString() {
        return "MO_LedgerAuditReport{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
