package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_CollateralRegistration
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_CollateralRegistration implements Serializable {

    private static final long serialVersionUID = 1L;

    private String collateralId;
    private String status;
    private double appraisedValue;
    private String messageCorrelationId;

    public MO_OUT_CollateralRegistration() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_CollateralRegistration(String collateralId, String status, double appraisedValue) {
        this();
        this.collateralId = collateralId;
        this.status = status;
        this.appraisedValue = appraisedValue;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getCollateralId() {
        return this.collateralId;
    }
    public void setCollateralId(String collateralId) {
        this.collateralId = collateralId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public double getAppraisedValue() {
        return this.appraisedValue;
    }
    public void setAppraisedValue(double appraisedValue) {
        this.appraisedValue = appraisedValue;
    }

    @Override
    public String toString() {
        return "MO_OUT_CollateralRegistration{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
