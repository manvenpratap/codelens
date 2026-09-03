package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_RateQuote
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_RateQuote implements Serializable {

    private static final long serialVersionUID = 1L;

    private double amount;
    private int tenureDays;
    private String customerCategory;
    private String messageCorrelationId;

    public MO_INP_RateQuote() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_RateQuote(double amount, int tenureDays, String customerCategory) {
        this();
        this.amount = amount;
        this.tenureDays = tenureDays;
        this.customerCategory = customerCategory;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }
    public int getTenureDays() {
        return this.tenureDays;
    }
    public void setTenureDays(int tenureDays) {
        this.tenureDays = tenureDays;
    }
    public String getCustomerCategory() {
        return this.customerCategory;
    }
    public void setCustomerCategory(String customerCategory) {
        this.customerCategory = customerCategory;
    }

    @Override
    public String toString() {
        return "MO_INP_RateQuote{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
