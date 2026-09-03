package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_JournalLegItem
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_JournalLegItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String glCode;
    private String side;
    private double amount;
    private String desc;
    private String messageCorrelationId;

    public MO_JournalLegItem() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_JournalLegItem(String glCode, String side, double amount, String desc) {
        this();
        this.glCode = glCode;
        this.side = side;
        this.amount = amount;
        this.desc = desc;
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
    public String getSide() {
        return this.side;
    }
    public void setSide(String side) {
        this.side = side;
    }
    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }
    public String getDesc() {
        return this.desc;
    }
    public void setDesc(String desc) {
        this.desc = desc;
    }

    @Override
    public String toString() {
        return "MO_JournalLegItem{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
