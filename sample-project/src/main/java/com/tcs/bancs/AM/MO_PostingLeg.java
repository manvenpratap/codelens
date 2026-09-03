package com.tcs.bancs.AM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_PostingLeg
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_PostingLeg implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accountNumber;
    private String entrySide;
    private double amount;
    private String glCode;
    private String narrative;
    private String messageCorrelationId;

    public MO_PostingLeg() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_PostingLeg(String accountNumber, String entrySide, double amount, String glCode, String narrative) {
        this();
        this.accountNumber = accountNumber;
        this.entrySide = entrySide;
        this.amount = amount;
        this.glCode = glCode;
        this.narrative = narrative;
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
    public String getEntrySide() {
        return this.entrySide;
    }
    public void setEntrySide(String entrySide) {
        this.entrySide = entrySide;
    }
    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }
    public String getGlCode() {
        return this.glCode;
    }
    public void setGlCode(String glCode) {
        this.glCode = glCode;
    }
    public String getNarrative() {
        return this.narrative;
    }
    public void setNarrative(String narrative) {
        this.narrative = narrative;
    }

    @Override
    public String toString() {
        return "MO_PostingLeg{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
