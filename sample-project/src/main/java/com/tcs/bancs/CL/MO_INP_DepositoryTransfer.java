package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_DepositoryTransfer
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_DepositoryTransfer implements Serializable {

    private static final long serialVersionUID = 1L;

    private String fromParticipant;
    private String toParticipant;
    private String isin;
    private int units;
    private String messageCorrelationId;

    public MO_INP_DepositoryTransfer() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_DepositoryTransfer(String fromParticipant, String toParticipant, String isin, int units) {
        this();
        this.fromParticipant = fromParticipant;
        this.toParticipant = toParticipant;
        this.isin = isin;
        this.units = units;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getFromParticipant() {
        return this.fromParticipant;
    }
    public void setFromParticipant(String fromParticipant) {
        this.fromParticipant = fromParticipant;
    }
    public String getToParticipant() {
        return this.toParticipant;
    }
    public void setToParticipant(String toParticipant) {
        this.toParticipant = toParticipant;
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

    @Override
    public String toString() {
        return "MO_INP_DepositoryTransfer{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
