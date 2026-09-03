package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_DepositBooking
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_DepositBooking implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private String fundingAccount;
    private double principal;
    private int tenureDays;
    private String productCode;
    private String messageCorrelationId;

    public MO_INP_DepositBooking() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_DepositBooking(String customerId, String fundingAccount, double principal, int tenureDays, String productCode) {
        this();
        this.customerId = customerId;
        this.fundingAccount = fundingAccount;
        this.principal = principal;
        this.tenureDays = tenureDays;
        this.productCode = productCode;
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
    public String getFundingAccount() {
        return this.fundingAccount;
    }
    public void setFundingAccount(String fundingAccount) {
        this.fundingAccount = fundingAccount;
    }
    public double getPrincipal() {
        return this.principal;
    }
    public void setPrincipal(double principal) {
        this.principal = principal;
    }
    public int getTenureDays() {
        return this.tenureDays;
    }
    public void setTenureDays(int tenureDays) {
        this.tenureDays = tenureDays;
    }
    public String getProductCode() {
        return this.productCode;
    }
    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    @Override
    public String toString() {
        return "MO_INP_DepositBooking{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
