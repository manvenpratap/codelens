package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_DepositoryBalance
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_DepositoryBalance implements Serializable {

    private static final long serialVersionUID = 1L;

    private String participant;
    private String isin;
    private int available;
    private int pledged;
    private String messageCorrelationId;

    public MO_DepositoryBalance() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_DepositoryBalance(String participant, String isin, int available, int pledged) {
        this();
        this.participant = participant;
        this.isin = isin;
        this.available = available;
        this.pledged = pledged;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getParticipant() {
        return this.participant;
    }
    public void setParticipant(String participant) {
        this.participant = participant;
    }
    public String getIsin() {
        return this.isin;
    }
    public void setIsin(String isin) {
        this.isin = isin;
    }
    public int getAvailable() {
        return this.available;
    }
    public void setAvailable(int available) {
        this.available = available;
    }
    public int getPledged() {
        return this.pledged;
    }
    public void setPledged(int pledged) {
        this.pledged = pledged;
    }

    @Override
    public String toString() {
        return "MO_DepositoryBalance{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
