package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_RateQuote
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_RateQuote implements Serializable {

    private static final long serialVersionUID = 1L;

    private double applicableRate;
    private double seniorCitizenBonus;
    private String validUntil;
    private String messageCorrelationId;

    public MO_OUT_RateQuote() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_RateQuote(double applicableRate, double seniorCitizenBonus, String validUntil) {
        this();
        this.applicableRate = applicableRate;
        this.seniorCitizenBonus = seniorCitizenBonus;
        this.validUntil = validUntil;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public double getApplicableRate() {
        return this.applicableRate;
    }
    public void setApplicableRate(double applicableRate) {
        this.applicableRate = applicableRate;
    }
    public double getSeniorCitizenBonus() {
        return this.seniorCitizenBonus;
    }
    public void setSeniorCitizenBonus(double seniorCitizenBonus) {
        this.seniorCitizenBonus = seniorCitizenBonus;
    }
    public String getValidUntil() {
        return this.validUntil;
    }
    public void setValidUntil(String validUntil) {
        this.validUntil = validUntil;
    }

    @Override
    public String toString() {
        return "MO_OUT_RateQuote{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
