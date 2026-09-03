package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_CollateralValuationReport
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_CollateralValuationReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private String collateralId;
    private double marketValue;
    private double haircut;
    private double netEligible;
    private String messageCorrelationId;

    public MO_CollateralValuationReport() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_CollateralValuationReport(String collateralId, double marketValue, double haircut, double netEligible) {
        this();
        this.collateralId = collateralId;
        this.marketValue = marketValue;
        this.haircut = haircut;
        this.netEligible = netEligible;
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
    public double getMarketValue() {
        return this.marketValue;
    }
    public void setMarketValue(double marketValue) {
        this.marketValue = marketValue;
    }
    public double getHaircut() {
        return this.haircut;
    }
    public void setHaircut(double haircut) {
        this.haircut = haircut;
    }
    public double getNetEligible() {
        return this.netEligible;
    }
    public void setNetEligible(double netEligible) {
        this.netEligible = netEligible;
    }

    @Override
    public String toString() {
        return "MO_CollateralValuationReport{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
