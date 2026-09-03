package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_PaymentStatusQuery
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_PaymentStatusQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private String paymentId;
    private String status;
    private String network;
    private double settledAmount;
    private String messageCorrelationId;

    public MO_OUT_PaymentStatusQuery() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_PaymentStatusQuery(String paymentId, String status, String network, double settledAmount) {
        this();
        this.paymentId = paymentId;
        this.status = status;
        this.network = network;
        this.settledAmount = settledAmount;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getPaymentId() {
        return this.paymentId;
    }
    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public String getNetwork() {
        return this.network;
    }
    public void setNetwork(String network) {
        this.network = network;
    }
    public double getSettledAmount() {
        return this.settledAmount;
    }
    public void setSettledAmount(double settledAmount) {
        this.settledAmount = settledAmount;
    }

    @Override
    public String toString() {
        return "MO_OUT_PaymentStatusQuery{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
