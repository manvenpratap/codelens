package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_PeriodClose
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_PeriodClose implements Serializable {

    private static final long serialVersionUID = 1L;

    private String periodId;
    private String status;
    private int unpostedCount;
    private String messageCorrelationId;

    public MO_OUT_PeriodClose() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_PeriodClose(String periodId, String status, int unpostedCount) {
        this();
        this.periodId = periodId;
        this.status = status;
        this.unpostedCount = unpostedCount;
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
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public int getUnpostedCount() {
        return this.unpostedCount;
    }
    public void setUnpostedCount(int unpostedCount) {
        this.unpostedCount = unpostedCount;
    }

    @Override
    public String toString() {
        return "MO_OUT_PeriodClose{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
