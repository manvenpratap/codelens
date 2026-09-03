package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_DispatchReceipt
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_DispatchReceipt implements Serializable {

    private static final long serialVersionUID = 1L;

    private String dispatchId;
    private String queue;
    private long ackTime;
    private String messageCorrelationId;

    public MO_DispatchReceipt() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_DispatchReceipt(String dispatchId, String queue, long ackTime) {
        this();
        this.dispatchId = dispatchId;
        this.queue = queue;
        this.ackTime = ackTime;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getDispatchId() {
        return this.dispatchId;
    }
    public void setDispatchId(String dispatchId) {
        this.dispatchId = dispatchId;
    }
    public String getQueue() {
        return this.queue;
    }
    public void setQueue(String queue) {
        this.queue = queue;
    }
    public long getAckTime() {
        return this.ackTime;
    }
    public void setAckTime(long ackTime) {
        this.ackTime = ackTime;
    }

    @Override
    public String toString() {
        return "MO_DispatchReceipt{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
