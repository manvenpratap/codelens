package com.tcs.bancs.SC;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_MarginCallIssue
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_MarginCallIssue implements Serializable {

    private static final long serialVersionUID = 1L;

    private String callId;
    private String status;
    private long deadline;
    private String messageCorrelationId;

    public MO_OUT_MarginCallIssue() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_MarginCallIssue(String callId, String status, long deadline) {
        this();
        this.callId = callId;
        this.status = status;
        this.deadline = deadline;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getCallId() {
        return this.callId;
    }
    public void setCallId(String callId) {
        this.callId = callId;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public long getDeadline() {
        return this.deadline;
    }
    public void setDeadline(long deadline) {
        this.deadline = deadline;
    }

    @Override
    public String toString() {
        return "MO_OUT_MarginCallIssue{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
