package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_PnLDecomposition
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_PnLDecomposition implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deskId;
    private double fxPnL;
    private double interestPnL;
    private double equityPnL;
    private String messageCorrelationId;

    public MO_PnLDecomposition() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_PnLDecomposition(String deskId, double fxPnL, double interestPnL, double equityPnL) {
        this();
        this.deskId = deskId;
        this.fxPnL = fxPnL;
        this.interestPnL = interestPnL;
        this.equityPnL = equityPnL;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getDeskId() {
        return this.deskId;
    }
    public void setDeskId(String deskId) {
        this.deskId = deskId;
    }
    public double getFxPnL() {
        return this.fxPnL;
    }
    public void setFxPnL(double fxPnL) {
        this.fxPnL = fxPnL;
    }
    public double getInterestPnL() {
        return this.interestPnL;
    }
    public void setInterestPnL(double interestPnL) {
        this.interestPnL = interestPnL;
    }
    public double getEquityPnL() {
        return this.equityPnL;
    }
    public void setEquityPnL(double equityPnL) {
        this.equityPnL = equityPnL;
    }

    @Override
    public String toString() {
        return "MO_PnLDecomposition{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
