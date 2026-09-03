package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_CustomerDormancyAlert
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_CustomerDormancyAlert implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private int daysInactive;
    private String recommendedAction;
    private String messageCorrelationId;

    public MO_CustomerDormancyAlert() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_CustomerDormancyAlert(String customerId, int daysInactive, String recommendedAction) {
        this();
        this.customerId = customerId;
        this.daysInactive = daysInactive;
        this.recommendedAction = recommendedAction;
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
    public int getDaysInactive() {
        return this.daysInactive;
    }
    public void setDaysInactive(int daysInactive) {
        this.daysInactive = daysInactive;
    }
    public String getRecommendedAction() {
        return this.recommendedAction;
    }
    public void setRecommendedAction(String recommendedAction) {
        this.recommendedAction = recommendedAction;
    }

    @Override
    public String toString() {
        return "MO_CustomerDormancyAlert{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
