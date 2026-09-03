package com.tcs.bancs.RK;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_RiskOverride
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_RiskOverride implements Serializable {

    private static final long serialVersionUID = 1L;

    private String overrideRef;
    private String status;
    private long effectiveUntil;
    private String messageCorrelationId;

    public MO_OUT_RiskOverride() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_RiskOverride(String overrideRef, String status, long effectiveUntil) {
        this();
        this.overrideRef = overrideRef;
        this.status = status;
        this.effectiveUntil = effectiveUntil;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getOverrideRef() {
        return this.overrideRef;
    }
    public void setOverrideRef(String overrideRef) {
        this.overrideRef = overrideRef;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public long getEffectiveUntil() {
        return this.effectiveUntil;
    }
    public void setEffectiveUntil(long effectiveUntil) {
        this.effectiveUntil = effectiveUntil;
    }

    @Override
    public String toString() {
        return "MO_OUT_RiskOverride{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
