package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_SettlementInstruct
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_SettlementInstruct implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tradeId;
    private String isin;
    private int units;
    private double amount;
    private String settlementDate;
    private String messageCorrelationId;

    public MO_INP_SettlementInstruct() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_SettlementInstruct(String tradeId, String isin, int units, double amount, String settlementDate) {
        this();
        this.tradeId = tradeId;
        this.isin = isin;
        this.units = units;
        this.amount = amount;
        this.settlementDate = settlementDate;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getTradeId() {
        return this.tradeId;
    }
    public void setTradeId(String tradeId) {
        this.tradeId = tradeId;
    }
    public String getIsin() {
        return this.isin;
    }
    public void setIsin(String isin) {
        this.isin = isin;
    }
    public int getUnits() {
        return this.units;
    }
    public void setUnits(int units) {
        this.units = units;
    }
    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }
    public String getSettlementDate() {
        return this.settlementDate;
    }
    public void setSettlementDate(String settlementDate) {
        this.settlementDate = settlementDate;
    }

    @Override
    public String toString() {
        return "MO_INP_SettlementInstruct{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
