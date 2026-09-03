package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_PaymentCancellation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_PaymentCancellation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String paymentId;
    private String cancellationReason;
    private String messageCorrelationId;

    public MO_INP_PaymentCancellation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_PaymentCancellation(String paymentId, String cancellationReason) {
        this();
        this.paymentId = paymentId;
        this.cancellationReason = cancellationReason;
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
    public String getCancellationReason() {
        return this.cancellationReason;
    }
    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    @Override
    public String toString() {
        return "MO_INP_PaymentCancellation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
