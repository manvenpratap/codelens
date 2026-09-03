package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_IsoPacs008
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_IsoPacs008 implements Serializable {

    private static final long serialVersionUID = 1L;

    private String endToEndId;
    private String debtorIban;
    private String creditorIban;
    private double interbankAmount;
    private String settlementDate;
    private String messageCorrelationId;

    public MO_INP_IsoPacs008() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_IsoPacs008(String endToEndId, String debtorIban, String creditorIban, double interbankAmount, String settlementDate) {
        this();
        this.endToEndId = endToEndId;
        this.debtorIban = debtorIban;
        this.creditorIban = creditorIban;
        this.interbankAmount = interbankAmount;
        this.settlementDate = settlementDate;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getEndToEndId() {
        return this.endToEndId;
    }
    public void setEndToEndId(String endToEndId) {
        this.endToEndId = endToEndId;
    }
    public String getDebtorIban() {
        return this.debtorIban;
    }
    public void setDebtorIban(String debtorIban) {
        this.debtorIban = debtorIban;
    }
    public String getCreditorIban() {
        return this.creditorIban;
    }
    public void setCreditorIban(String creditorIban) {
        this.creditorIban = creditorIban;
    }
    public double getInterbankAmount() {
        return this.interbankAmount;
    }
    public void setInterbankAmount(double interbankAmount) {
        this.interbankAmount = interbankAmount;
    }
    public String getSettlementDate() {
        return this.settlementDate;
    }
    public void setSettlementDate(String settlementDate) {
        this.settlementDate = settlementDate;
    }

    @Override
    public String toString() {
        return "MO_INP_IsoPacs008{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
