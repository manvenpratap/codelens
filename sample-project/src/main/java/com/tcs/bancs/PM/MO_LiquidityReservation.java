package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_LiquidityReservation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_LiquidityReservation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String network;
    private double reservedAmount;
    private String status;
    private String messageCorrelationId;

    public MO_LiquidityReservation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_LiquidityReservation(String network, double reservedAmount, String status) {
        this();
        this.network = network;
        this.reservedAmount = reservedAmount;
        this.status = status;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getNetwork() {
        return this.network;
    }
    public void setNetwork(String network) {
        this.network = network;
    }
    public double getReservedAmount() {
        return this.reservedAmount;
    }
    public void setReservedAmount(double reservedAmount) {
        this.reservedAmount = reservedAmount;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MO_LiquidityReservation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
