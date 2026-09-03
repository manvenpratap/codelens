package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_TrialBalanceItem
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_TrialBalanceItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String glCode;
    private String glName;
    private double debit;
    private double credit;
    private String messageCorrelationId;

    public MO_TrialBalanceItem() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_TrialBalanceItem(String glCode, String glName, double debit, double credit) {
        this();
        this.glCode = glCode;
        this.glName = glName;
        this.debit = debit;
        this.credit = credit;
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
    public String getGlName() {
        return this.glName;
    }
    public void setGlName(String glName) {
        this.glName = glName;
    }
    public double getDebit() {
        return this.debit;
    }
    public void setDebit(double debit) {
        this.debit = debit;
    }
    public double getCredit() {
        return this.credit;
    }
    public void setCredit(double credit) {
        this.credit = credit;
    }

    @Override
    public String toString() {
        return "MO_TrialBalanceItem{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
