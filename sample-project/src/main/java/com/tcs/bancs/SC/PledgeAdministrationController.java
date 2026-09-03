package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PledgeAdministrationController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class PledgeAdministrationController {

    private final SCBTCapitalizePledge businessTransaction;
    private final SCETQueryActivePledges elementaryTransaction;

    public PledgeAdministrationController() {
        this.businessTransaction = new SCBTCapitalizePledge();
        this.elementaryTransaction = new SCETQueryActivePledges();
    }

    public PledgeAdministrationController(SCBTCapitalizePledge bt, SCETQueryActivePledges et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_PledgeCreation handleExecuteRequest(MO_INP_PledgeCreation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PledgeAdministrationController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.SCBTCapitalizePledgeExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_PledgeCreation handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PledgeAdministrationController", queryKey, "INQUIRY");
        return this.elementaryTransaction.SCETQueryActivePledgesFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
