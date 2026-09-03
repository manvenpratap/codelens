package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_OUT_BeneficialOwnerDeclaration
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_OUT_BeneficialOwnerDeclaration implements Serializable {

    private static final long serialVersionUID = 1L;

    private String declarationId;
    private boolean verified;
    private String messageCorrelationId;

    public MO_OUT_BeneficialOwnerDeclaration() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_OUT_BeneficialOwnerDeclaration(String declarationId, boolean verified) {
        this();
        this.declarationId = declarationId;
        this.verified = verified;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getDeclarationId() {
        return this.declarationId;
    }
    public void setDeclarationId(String declarationId) {
        this.declarationId = declarationId;
    }
    public boolean getVerified() {
        return this.verified;
    }
    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    @Override
    public String toString() {
        return "MO_OUT_BeneficialOwnerDeclaration{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
