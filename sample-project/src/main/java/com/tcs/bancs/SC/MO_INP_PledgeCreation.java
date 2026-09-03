package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_PledgeCreation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_PledgeCreation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String collateralId;
    private String facilityId;
    private double pledgeAmount;
    private int priority;
    private String messageCorrelationId;

    public MO_INP_PledgeCreation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_PledgeCreation(String collateralId, String facilityId, double pledgeAmount, int priority) {
        this();
        this.collateralId = collateralId;
        this.facilityId = facilityId;
        this.pledgeAmount = pledgeAmount;
        this.priority = priority;
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
    public String getFacilityId() {
        return this.facilityId;
    }
    public void setFacilityId(String facilityId) {
        this.facilityId = facilityId;
    }
    public double getPledgeAmount() {
        return this.pledgeAmount;
    }
    public void setPledgeAmount(double pledgeAmount) {
        this.pledgeAmount = pledgeAmount;
    }
    public int getPriority() {
        return this.priority;
    }
    public void setPriority(int priority) {
        this.priority = priority;
    }

    @Override
    public String toString() {
        return "MO_INP_PledgeCreation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
