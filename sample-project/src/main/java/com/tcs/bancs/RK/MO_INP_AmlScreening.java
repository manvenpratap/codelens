package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_AmlScreening
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_AmlScreening implements Serializable {

    private static final long serialVersionUID = 1L;

    private String transactionId;
    private String senderId;
    private String receiverId;
    private double amount;
    private String countryCode;
    private String messageCorrelationId;

    public MO_INP_AmlScreening() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_AmlScreening(String transactionId, String senderId, String receiverId, double amount, String countryCode) {
        this();
        this.transactionId = transactionId;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.amount = amount;
        this.countryCode = countryCode;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getTransactionId() {
        return this.transactionId;
    }
    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
    public String getSenderId() {
        return this.senderId;
    }
    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }
    public String getReceiverId() {
        return this.receiverId;
    }
    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }
    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }
    public String getCountryCode() {
        return this.countryCode;
    }
    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    @Override
    public String toString() {
        return "MO_INP_AmlScreening{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
