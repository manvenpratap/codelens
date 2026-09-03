package com.tcs.bancs.AN;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_AttributionFactor
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_AttributionFactor implements Serializable {

    private static final long serialVersionUID = 1L;

    private String factorName;
    private double weight;
    private double contribution;
    private String messageCorrelationId;

    public MO_AttributionFactor() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_AttributionFactor(String factorName, double weight, double contribution) {
        this();
        this.factorName = factorName;
        this.weight = weight;
        this.contribution = contribution;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getFactorName() {
        return this.factorName;
    }
    public void setFactorName(String factorName) {
        this.factorName = factorName;
    }
    public double getWeight() {
        return this.weight;
    }
    public void setWeight(double weight) {
        this.weight = weight;
    }
    public double getContribution() {
        return this.contribution;
    }
    public void setContribution(double contribution) {
        this.contribution = contribution;
    }

    @Override
    public String toString() {
        return "MO_AttributionFactor{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
