package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_LtvBreachAlert
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_LtvBreachAlert implements Serializable {

    private static final long serialVersionUID = 1L;

    private String facilityId;
    private double currentLtv;
    private double covenantLtv;
    private double shortfall;
    private String messageCorrelationId;

    public MO_LtvBreachAlert() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_LtvBreachAlert(String facilityId, double currentLtv, double covenantLtv, double shortfall) {
        this();
        this.facilityId = facilityId;
        this.currentLtv = currentLtv;
        this.covenantLtv = covenantLtv;
        this.shortfall = shortfall;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getFacilityId() {
        return this.facilityId;
    }
    public void setFacilityId(String facilityId) {
        this.facilityId = facilityId;
    }
    public double getCurrentLtv() {
        return this.currentLtv;
    }
    public void setCurrentLtv(double currentLtv) {
        this.currentLtv = currentLtv;
    }
    public double getCovenantLtv() {
        return this.covenantLtv;
    }
    public void setCovenantLtv(double covenantLtv) {
        this.covenantLtv = covenantLtv;
    }
    public double getShortfall() {
        return this.shortfall;
    }
    public void setShortfall(double shortfall) {
        this.shortfall = shortfall;
    }

    @Override
    public String toString() {
        return "MO_LtvBreachAlert{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
