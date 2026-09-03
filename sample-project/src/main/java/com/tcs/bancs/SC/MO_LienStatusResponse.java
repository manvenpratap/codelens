package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_LienStatusResponse
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_LienStatusResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String collateralId;
    private String lienStatus;
    private double encumbered;
    private String messageCorrelationId;

    public MO_LienStatusResponse() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_LienStatusResponse(String collateralId, String lienStatus, double encumbered) {
        this();
        this.collateralId = collateralId;
        this.lienStatus = lienStatus;
        this.encumbered = encumbered;
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
    public String getLienStatus() {
        return this.lienStatus;
    }
    public void setLienStatus(String lienStatus) {
        this.lienStatus = lienStatus;
    }
    public double getEncumbered() {
        return this.encumbered;
    }
    public void setEncumbered(double encumbered) {
        this.encumbered = encumbered;
    }

    @Override
    public String toString() {
        return "MO_LienStatusResponse{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
