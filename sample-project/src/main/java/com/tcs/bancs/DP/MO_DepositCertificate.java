package com.tcs.bancs.DP;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_DepositCertificate
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_DepositCertificate implements Serializable {

    private static final long serialVersionUID = 1L;

    private String certificateNumber;
    private String depositId;
    private String holderName;
    private double principal;
    private double maturityValue;
    private String messageCorrelationId;

    public MO_DepositCertificate() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_DepositCertificate(String certificateNumber, String depositId, String holderName, double principal, double maturityValue) {
        this();
        this.certificateNumber = certificateNumber;
        this.depositId = depositId;
        this.holderName = holderName;
        this.principal = principal;
        this.maturityValue = maturityValue;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getCertificateNumber() {
        return this.certificateNumber;
    }
    public void setCertificateNumber(String certificateNumber) {
        this.certificateNumber = certificateNumber;
    }
    public String getDepositId() {
        return this.depositId;
    }
    public void setDepositId(String depositId) {
        this.depositId = depositId;
    }
    public String getHolderName() {
        return this.holderName;
    }
    public void setHolderName(String holderName) {
        this.holderName = holderName;
    }
    public double getPrincipal() {
        return this.principal;
    }
    public void setPrincipal(double principal) {
        this.principal = principal;
    }
    public double getMaturityValue() {
        return this.maturityValue;
    }
    public void setMaturityValue(double maturityValue) {
        this.maturityValue = maturityValue;
    }

    @Override
    public String toString() {
        return "MO_DepositCertificate{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
