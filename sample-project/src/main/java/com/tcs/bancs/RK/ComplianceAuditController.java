package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: ComplianceAuditController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class ComplianceAuditController {

    private final RKBTProcessAmlAlert businessTransaction;
    private final RKETCheckCounterpartyLimit elementaryTransaction;

    public ComplianceAuditController() {
        this.businessTransaction = new RKBTProcessAmlAlert();
        this.elementaryTransaction = new RKETCheckCounterpartyLimit();
    }

    public ComplianceAuditController(RKBTProcessAmlAlert bt, RKETCheckCounterpartyLimit et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_AmlScreening handleExecuteRequest(MO_INP_AmlScreening request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ComplianceAuditController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.RKBTProcessAmlAlertExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_AmlScreening handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ComplianceAuditController", queryKey, "INQUIRY");
        return this.elementaryTransaction.RKETCheckCounterpartyLimitFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
