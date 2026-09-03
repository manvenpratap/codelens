package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_PnLCalculation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_PnLCalculation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String pnlId;
    private double totalPnL;
    private double realized;
    private double unrealized;
    private String messageCorrelationId;

    public MO_OUT_PnLCalculation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_PnLCalculation(String pnlId, double totalPnL, double realized, double unrealized) {
        this();
        this.pnlId = pnlId;
        this.totalPnL = totalPnL;
        this.realized = realized;
        this.unrealized = unrealized;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getPnlId() {
        return this.pnlId;
    }
    public void setPnlId(String pnlId) {
        this.pnlId = pnlId;
    }
    public double getTotalPnL() {
        return this.totalPnL;
    }
    public void setTotalPnL(double totalPnL) {
        this.totalPnL = totalPnL;
    }
    public double getRealized() {
        return this.realized;
    }
    public void setRealized(double realized) {
        this.realized = realized;
    }
    public double getUnrealized() {
        return this.unrealized;
    }
    public void setUnrealized(double unrealized) {
        this.unrealized = unrealized;
    }

    @Override
    public String toString() {
        return "MO_OUT_PnLCalculation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
