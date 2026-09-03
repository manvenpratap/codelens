package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_SettlementObligation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_SettlementObligation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String memberId;
    private String isin;
    private int deliverUnits;
    private double payCash;
    private String messageCorrelationId;

    public MO_SettlementObligation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_SettlementObligation(String memberId, String isin, int deliverUnits, double payCash) {
        this();
        this.memberId = memberId;
        this.isin = isin;
        this.deliverUnits = deliverUnits;
        this.payCash = payCash;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getMemberId() {
        return this.memberId;
    }
    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }
    public String getIsin() {
        return this.isin;
    }
    public void setIsin(String isin) {
        this.isin = isin;
    }
    public int getDeliverUnits() {
        return this.deliverUnits;
    }
    public void setDeliverUnits(int deliverUnits) {
        this.deliverUnits = deliverUnits;
    }
    public double getPayCash() {
        return this.payCash;
    }
    public void setPayCash(double payCash) {
        this.payCash = payCash;
    }

    @Override
    public String toString() {
        return "MO_SettlementObligation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
