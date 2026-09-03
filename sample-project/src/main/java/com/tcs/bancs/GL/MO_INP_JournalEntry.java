package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_JournalEntry
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_JournalEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private String ref;
    private String date;
    private String voucherType;
    private String drGlCode;
    private String crGlCode;
    private double amount;
    private String narration;
    private String messageCorrelationId;

    public MO_INP_JournalEntry() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_JournalEntry(String ref, String date, String voucherType, String drGlCode, String crGlCode, double amount, String narration) {
        this();
        this.ref = ref;
        this.date = date;
        this.voucherType = voucherType;
        this.drGlCode = drGlCode;
        this.crGlCode = crGlCode;
        this.amount = amount;
        this.narration = narration;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getRef() {
        return this.ref;
    }
    public void setRef(String ref) {
        this.ref = ref;
    }
    public String getDate() {
        return this.date;
    }
    public void setDate(String date) {
        this.date = date;
    }
    public String getVoucherType() {
        return this.voucherType;
    }
    public void setVoucherType(String voucherType) {
        this.voucherType = voucherType;
    }
    public String getDrGlCode() {
        return this.drGlCode;
    }
    public void setDrGlCode(String drGlCode) {
        this.drGlCode = drGlCode;
    }
    public String getCrGlCode() {
        return this.crGlCode;
    }
    public void setCrGlCode(String crGlCode) {
        this.crGlCode = crGlCode;
    }
    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }
    public String getNarration() {
        return this.narration;
    }
    public void setNarration(String narration) {
        this.narration = narration;
    }

    @Override
    public String toString() {
        return "MO_INP_JournalEntry{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
