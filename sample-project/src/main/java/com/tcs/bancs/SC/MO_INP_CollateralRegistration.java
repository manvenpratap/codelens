package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_CollateralRegistration
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_CollateralRegistration implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private String type;
    private String description;
    private double estimatedValue;
    private double haircut;
    private String messageCorrelationId;

    public MO_INP_CollateralRegistration() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_CollateralRegistration(String customerId, String type, String description, double estimatedValue, double haircut) {
        this();
        this.customerId = customerId;
        this.type = type;
        this.description = description;
        this.estimatedValue = estimatedValue;
        this.haircut = haircut;
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
    public String getType() {
        return this.type;
    }
    public void setType(String type) {
        this.type = type;
    }
    public String getDescription() {
        return this.description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
    public double getEstimatedValue() {
        return this.estimatedValue;
    }
    public void setEstimatedValue(double estimatedValue) {
        this.estimatedValue = estimatedValue;
    }
    public double getHaircut() {
        return this.haircut;
    }
    public void setHaircut(double haircut) {
        this.haircut = haircut;
    }

    @Override
    public String toString() {
        return "MO_INP_CollateralRegistration{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
