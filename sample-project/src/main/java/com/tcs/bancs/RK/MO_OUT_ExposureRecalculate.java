package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_ExposureRecalculate
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_ExposureRecalculate implements Serializable {

    private static final long serialVersionUID = 1L;

    private String exposureId;
    private double totalNetExposure;
    private boolean breach;
    private String messageCorrelationId;

    public MO_OUT_ExposureRecalculate() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_ExposureRecalculate(String exposureId, double totalNetExposure, boolean breach) {
        this();
        this.exposureId = exposureId;
        this.totalNetExposure = totalNetExposure;
        this.breach = breach;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getExposureId() {
        return this.exposureId;
    }
    public void setExposureId(String exposureId) {
        this.exposureId = exposureId;
    }
    public double getTotalNetExposure() {
        return this.totalNetExposure;
    }
    public void setTotalNetExposure(double totalNetExposure) {
        this.totalNetExposure = totalNetExposure;
    }
    public boolean getBreach() {
        return this.breach;
    }
    public void setBreach(boolean breach) {
        this.breach = breach;
    }

    @Override
    public String toString() {
        return "MO_OUT_ExposureRecalculate{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
