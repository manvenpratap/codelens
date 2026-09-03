package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_CollateralRevaluation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_CollateralRevaluation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String collateralId;
    private double oldAppraised;
    private double newAppraised;
    private double changePct;
    private String messageCorrelationId;

    public MO_OUT_CollateralRevaluation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_CollateralRevaluation(String collateralId, double oldAppraised, double newAppraised, double changePct) {
        this();
        this.collateralId = collateralId;
        this.oldAppraised = oldAppraised;
        this.newAppraised = newAppraised;
        this.changePct = changePct;
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
    public double getOldAppraised() {
        return this.oldAppraised;
    }
    public void setOldAppraised(double oldAppraised) {
        this.oldAppraised = oldAppraised;
    }
    public double getNewAppraised() {
        return this.newAppraised;
    }
    public void setNewAppraised(double newAppraised) {
        this.newAppraised = newAppraised;
    }
    public double getChangePct() {
        return this.changePct;
    }
    public void setChangePct(double changePct) {
        this.changePct = changePct;
    }

    @Override
    public String toString() {
        return "MO_OUT_CollateralRevaluation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
