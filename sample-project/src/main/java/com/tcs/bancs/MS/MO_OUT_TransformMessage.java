package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_TransformMessage
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_TransformMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String transformedPayload;
    private boolean success;
    private String error;
    private String messageCorrelationId;

    public MO_OUT_TransformMessage() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_TransformMessage(String transformedPayload, boolean success, String error) {
        this();
        this.transformedPayload = transformedPayload;
        this.success = success;
        this.error = error;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getTransformedPayload() {
        return this.transformedPayload;
    }
    public void setTransformedPayload(String transformedPayload) {
        this.transformedPayload = transformedPayload;
    }
    public boolean getSuccess() {
        return this.success;
    }
    public void setSuccess(boolean success) {
        this.success = success;
    }
    public String getError() {
        return this.error;
    }
    public void setError(String error) {
        this.error = error;
    }

    @Override
    public String toString() {
        return "MO_OUT_TransformMessage{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
