package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_DeadLetterNotice
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_DeadLetterNotice implements Serializable {

    private static final long serialVersionUID = 1L;

    private String messageId;
    private String exceptionMsg;
    private int attempts;
    private String messageCorrelationId;

    public MO_DeadLetterNotice() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_DeadLetterNotice(String messageId, String exceptionMsg, int attempts) {
        this();
        this.messageId = messageId;
        this.exceptionMsg = exceptionMsg;
        this.attempts = attempts;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getMessageId() {
        return this.messageId;
    }
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    public String getExceptionMsg() {
        return this.exceptionMsg;
    }
    public void setExceptionMsg(String exceptionMsg) {
        this.exceptionMsg = exceptionMsg;
    }
    public int getAttempts() {
        return this.attempts;
    }
    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    @Override
    public String toString() {
        return "MO_DeadLetterNotice{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
