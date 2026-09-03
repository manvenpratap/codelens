package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_Affirmation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_Affirmation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String instructionId;
    private String matchStatus;
    private long timestamp;
    private String messageCorrelationId;

    public MO_OUT_Affirmation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_Affirmation(String instructionId, String matchStatus, long timestamp) {
        this();
        this.instructionId = instructionId;
        this.matchStatus = matchStatus;
        this.timestamp = timestamp;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getInstructionId() {
        return this.instructionId;
    }
    public void setInstructionId(String instructionId) {
        this.instructionId = instructionId;
    }
    public String getMatchStatus() {
        return this.matchStatus;
    }
    public void setMatchStatus(String matchStatus) {
        this.matchStatus = matchStatus;
    }
    public long getTimestamp() {
        return this.timestamp;
    }
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "MO_OUT_Affirmation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
