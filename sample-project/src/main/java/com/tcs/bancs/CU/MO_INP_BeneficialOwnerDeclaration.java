package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_BeneficialOwnerDeclaration
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_BeneficialOwnerDeclaration implements Serializable {

    private static final long serialVersionUID = 1L;

    private String corporateCustomerId;
    private String individualName;
    private double ownershipPct;
    private String messageCorrelationId;

    public MO_INP_BeneficialOwnerDeclaration() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_BeneficialOwnerDeclaration(String corporateCustomerId, String individualName, double ownershipPct) {
        this();
        this.corporateCustomerId = corporateCustomerId;
        this.individualName = individualName;
        this.ownershipPct = ownershipPct;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getCorporateCustomerId() {
        return this.corporateCustomerId;
    }
    public void setCorporateCustomerId(String corporateCustomerId) {
        this.corporateCustomerId = corporateCustomerId;
    }
    public String getIndividualName() {
        return this.individualName;
    }
    public void setIndividualName(String individualName) {
        this.individualName = individualName;
    }
    public double getOwnershipPct() {
        return this.ownershipPct;
    }
    public void setOwnershipPct(double ownershipPct) {
        this.ownershipPct = ownershipPct;
    }

    @Override
    public String toString() {
        return "MO_INP_BeneficialOwnerDeclaration{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
