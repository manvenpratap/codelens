package com.tcs.bancs.PM;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_PaymentRoutingPath
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_PaymentRoutingPath implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sourceBic;
    private String destBic;
    private String optimalNetwork;
    private double estimatedFee;
    private String messageCorrelationId;

    public MO_PaymentRoutingPath() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_PaymentRoutingPath(String sourceBic, String destBic, String optimalNetwork, double estimatedFee) {
        this();
        this.sourceBic = sourceBic;
        this.destBic = destBic;
        this.optimalNetwork = optimalNetwork;
        this.estimatedFee = estimatedFee;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getSourceBic() {
        return this.sourceBic;
    }
    public void setSourceBic(String sourceBic) {
        this.sourceBic = sourceBic;
    }
    public String getDestBic() {
        return this.destBic;
    }
    public void setDestBic(String destBic) {
        this.destBic = destBic;
    }
    public String getOptimalNetwork() {
        return this.optimalNetwork;
    }
    public void setOptimalNetwork(String optimalNetwork) {
        this.optimalNetwork = optimalNetwork;
    }
    public double getEstimatedFee() {
        return this.estimatedFee;
    }
    public void setEstimatedFee(double estimatedFee) {
        this.estimatedFee = estimatedFee;
    }

    @Override
    public String toString() {
        return "MO_PaymentRoutingPath{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
