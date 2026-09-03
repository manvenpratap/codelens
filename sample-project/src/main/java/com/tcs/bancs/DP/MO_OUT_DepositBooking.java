package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_DepositBooking
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_DepositBooking implements Serializable {

    private static final long serialVersionUID = 1L;

    private String depositId;
    private String status;
    private double maturityAmount;
    private String maturityDate;
    private double interestRate;
    private String messageCorrelationId;

    public MO_OUT_DepositBooking() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_DepositBooking(String depositId, String status, double maturityAmount, String maturityDate, double interestRate) {
        this();
        this.depositId = depositId;
        this.status = status;
        this.maturityAmount = maturityAmount;
        this.maturityDate = maturityDate;
        this.interestRate = interestRate;
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
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public double getMaturityAmount() {
        return this.maturityAmount;
    }
    public void setMaturityAmount(double maturityAmount) {
        this.maturityAmount = maturityAmount;
    }
    public String getMaturityDate() {
        return this.maturityDate;
    }
    public void setMaturityDate(String maturityDate) {
        this.maturityDate = maturityDate;
    }
    public double getInterestRate() {
        return this.interestRate;
    }
    public void setInterestRate(double interestRate) {
        this.interestRate = interestRate;
    }

    @Override
    public String toString() {
        return "MO_OUT_DepositBooking{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
