package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_PeriodClose
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_PeriodClose implements Serializable {

    private static final long serialVersionUID = 1L;

    private String periodId;
    private String closedBy;
    private String messageCorrelationId;

    public MO_INP_PeriodClose() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_PeriodClose(String periodId, String closedBy) {
        this();
        this.periodId = periodId;
        this.closedBy = closedBy;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getPeriodId() {
        return this.periodId;
    }
    public void setPeriodId(String periodId) {
        this.periodId = periodId;
    }
    public String getClosedBy() {
        return this.closedBy;
    }
    public void setClosedBy(String closedBy) {
        this.closedBy = closedBy;
    }

    @Override
    public String toString() {
        return "MO_INP_PeriodClose{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
