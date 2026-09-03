package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_PaymentStatusQuery
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_PaymentStatusQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private String paymentId;
    private String endToEndId;
    private String messageCorrelationId;

    public MO_INP_PaymentStatusQuery() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_PaymentStatusQuery(String paymentId, String endToEndId) {
        this();
        this.paymentId = paymentId;
        this.endToEndId = endToEndId;
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
    public String getEndToEndId() {
        return this.endToEndId;
    }
    public void setEndToEndId(String endToEndId) {
        this.endToEndId = endToEndId;
    }

    @Override
    public String toString() {
        return "MO_INP_PaymentStatusQuery{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
