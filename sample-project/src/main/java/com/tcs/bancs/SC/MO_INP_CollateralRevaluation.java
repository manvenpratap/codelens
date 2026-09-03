package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_CollateralRevaluation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_CollateralRevaluation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String collateralId;
    private double newMarkVal;
    private String appraisalRef;
    private String messageCorrelationId;

    public MO_INP_CollateralRevaluation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_CollateralRevaluation(String collateralId, double newMarkVal, String appraisalRef) {
        this();
        this.collateralId = collateralId;
        this.newMarkVal = newMarkVal;
        this.appraisalRef = appraisalRef;
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
    public double getNewMarkVal() {
        return this.newMarkVal;
    }
    public void setNewMarkVal(double newMarkVal) {
        this.newMarkVal = newMarkVal;
    }
    public String getAppraisalRef() {
        return this.appraisalRef;
    }
    public void setAppraisalRef(String appraisalRef) {
        this.appraisalRef = appraisalRef;
    }

    @Override
    public String toString() {
        return "MO_INP_CollateralRevaluation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
