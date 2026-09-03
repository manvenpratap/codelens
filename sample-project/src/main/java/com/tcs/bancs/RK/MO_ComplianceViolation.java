package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_ComplianceViolation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_ComplianceViolation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String alertId;
    private String severity;
    private String entityId;
    private String description;
    private String messageCorrelationId;

    public MO_ComplianceViolation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_ComplianceViolation(String alertId, String severity, String entityId, String description) {
        this();
        this.alertId = alertId;
        this.severity = severity;
        this.entityId = entityId;
        this.description = description;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getAlertId() {
        return this.alertId;
    }
    public void setAlertId(String alertId) {
        this.alertId = alertId;
    }
    public String getSeverity() {
        return this.severity;
    }
    public void setSeverity(String severity) {
        this.severity = severity;
    }
    public String getEntityId() {
        return this.entityId;
    }
    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }
    public String getDescription() {
        return this.description;
    }
    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return "MO_ComplianceViolation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
