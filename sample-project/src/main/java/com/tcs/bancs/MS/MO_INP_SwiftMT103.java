package com.tcs.bancs.MS;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_SwiftMT103
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_SwiftMT103 implements Serializable {

    private static final long serialVersionUID = 1L;

    private String senderBic;
    private String receiverBic;
    private String orderingCustomer;
    private String beneficiaryCustomer;
    private double amount;
    private String currency;
    private String messageCorrelationId;

    public MO_INP_SwiftMT103() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_SwiftMT103(String senderBic, String receiverBic, String orderingCustomer, String beneficiaryCustomer, double amount, String currency) {
        this();
        this.senderBic = senderBic;
        this.receiverBic = receiverBic;
        this.orderingCustomer = orderingCustomer;
        this.beneficiaryCustomer = beneficiaryCustomer;
        this.amount = amount;
        this.currency = currency;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getSenderBic() {
        return this.senderBic;
    }
    public void setSenderBic(String senderBic) {
        this.senderBic = senderBic;
    }
    public String getReceiverBic() {
        return this.receiverBic;
    }
    public void setReceiverBic(String receiverBic) {
        this.receiverBic = receiverBic;
    }
    public String getOrderingCustomer() {
        return this.orderingCustomer;
    }
    public void setOrderingCustomer(String orderingCustomer) {
        this.orderingCustomer = orderingCustomer;
    }
    public String getBeneficiaryCustomer() {
        return this.beneficiaryCustomer;
    }
    public void setBeneficiaryCustomer(String beneficiaryCustomer) {
        this.beneficiaryCustomer = beneficiaryCustomer;
    }
    public double getAmount() {
        return this.amount;
    }
    public void setAmount(double amount) {
        this.amount = amount;
    }
    public String getCurrency() {
        return this.currency;
    }
    public void setCurrency(String currency) {
        this.currency = currency;
    }

    @Override
    public String toString() {
        return "MO_INP_SwiftMT103{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
