package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_TrialBalanceQuery
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_TrialBalanceQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private int fiscalYear;
    private int periodNumber;
    private String currency;
    private String messageCorrelationId;

    public MO_INP_TrialBalanceQuery() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_TrialBalanceQuery(int fiscalYear, int periodNumber, String currency) {
        this();
        this.fiscalYear = fiscalYear;
        this.periodNumber = periodNumber;
        this.currency = currency;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public int getFiscalYear() {
        return this.fiscalYear;
    }
    public void setFiscalYear(int fiscalYear) {
        this.fiscalYear = fiscalYear;
    }
    public int getPeriodNumber() {
        return this.periodNumber;
    }
    public void setPeriodNumber(int periodNumber) {
        this.periodNumber = periodNumber;
    }
    public String getCurrency() {
        return this.currency;
    }
    public void setCurrency(String currency) {
        this.currency = currency;
    }

    @Override
    public String toString() {
        return "MO_INP_TrialBalanceQuery{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
