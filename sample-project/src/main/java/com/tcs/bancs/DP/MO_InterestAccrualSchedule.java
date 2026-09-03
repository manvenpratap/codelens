package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_InterestAccrualSchedule
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_InterestAccrualSchedule implements Serializable {

    private static final long serialVersionUID = 1L;

    private String depositId;
    private int periods;
    private double totalExpectedInterest;
    private String messageCorrelationId;

    public MO_InterestAccrualSchedule() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_InterestAccrualSchedule(String depositId, int periods, double totalExpectedInterest) {
        this();
        this.depositId = depositId;
        this.periods = periods;
        this.totalExpectedInterest = totalExpectedInterest;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getDepositId() {
        return this.depositId;
    }
    public void setDepositId(String depositId) {
        this.depositId = depositId;
    }
    public int getPeriods() {
        return this.periods;
    }
    public void setPeriods(int periods) {
        this.periods = periods;
    }
    public double getTotalExpectedInterest() {
        return this.totalExpectedInterest;
    }
    public void setTotalExpectedInterest(double totalExpectedInterest) {
        this.totalExpectedInterest = totalExpectedInterest;
    }

    @Override
    public String toString() {
        return "MO_InterestAccrualSchedule{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
