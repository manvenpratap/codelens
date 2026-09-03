package com.tcs.bancs.CL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_Affirmation
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_Affirmation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String instructionId;
    private String affirmingParty;
    private boolean isAffirmed;
    private String messageCorrelationId;

    public MO_INP_Affirmation() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_Affirmation(String instructionId, String affirmingParty, boolean isAffirmed) {
        this();
        this.instructionId = instructionId;
        this.affirmingParty = affirmingParty;
        this.isAffirmed = isAffirmed;
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
    public String getAffirmingParty() {
        return this.affirmingParty;
    }
    public void setAffirmingParty(String affirmingParty) {
        this.affirmingParty = affirmingParty;
    }
    public boolean getIsAffirmed() {
        return this.isAffirmed;
    }
    public void setIsAffirmed(boolean isAffirmed) {
        this.isAffirmed = isAffirmed;
    }

    @Override
    public String toString() {
        return "MO_INP_Affirmation{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
