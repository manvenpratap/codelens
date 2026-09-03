package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_CustomerCreditScore
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_CustomerCreditScore implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private int bureauScore;
    private String bureauAgency;
    private String messageCorrelationId;

    public MO_CustomerCreditScore() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_CustomerCreditScore(String customerId, int bureauScore, String bureauAgency) {
        this();
        this.customerId = customerId;
        this.bureauScore = bureauScore;
        this.bureauAgency = bureauAgency;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getCustomerId() {
        return this.customerId;
    }
    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
    public int getBureauScore() {
        return this.bureauScore;
    }
    public void setBureauScore(int bureauScore) {
        this.bureauScore = bureauScore;
    }
    public String getBureauAgency() {
        return this.bureauAgency;
    }
    public void setBureauAgency(String bureauAgency) {
        this.bureauAgency = bureauAgency;
    }

    @Override
    public String toString() {
        return "MO_CustomerCreditScore{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
