package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_CustomerRelationshipMap
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_CustomerRelationshipMap implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private int subsidiaryCount;
    private double groupExposure;
    private String messageCorrelationId;

    public MO_CustomerRelationshipMap() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_CustomerRelationshipMap(String customerId, int subsidiaryCount, double groupExposure) {
        this();
        this.customerId = customerId;
        this.subsidiaryCount = subsidiaryCount;
        this.groupExposure = groupExposure;
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
    public int getSubsidiaryCount() {
        return this.subsidiaryCount;
    }
    public void setSubsidiaryCount(int subsidiaryCount) {
        this.subsidiaryCount = subsidiaryCount;
    }
    public double getGroupExposure() {
        return this.groupExposure;
    }
    public void setGroupExposure(double groupExposure) {
        this.groupExposure = groupExposure;
    }

    @Override
    public String toString() {
        return "MO_CustomerRelationshipMap{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
