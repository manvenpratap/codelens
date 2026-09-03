package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: RegulatoryComplianceController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class RegulatoryComplianceController {

    private final ANBTGenerateRegulatoryFiling businessTransaction;
    private final ANETQueryCapitalAdequacy elementaryTransaction;

    public RegulatoryComplianceController() {
        this.businessTransaction = new ANBTGenerateRegulatoryFiling();
        this.elementaryTransaction = new ANETQueryCapitalAdequacy();
    }

    public RegulatoryComplianceController(ANBTGenerateRegulatoryFiling bt, ANETQueryCapitalAdequacy et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_BaselReportGenerate handleExecuteRequest(MO_INP_BaselReportGenerate request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "RegulatoryComplianceController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.ANBTGenerateRegulatoryFilingExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_BaselReportGenerate handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "RegulatoryComplianceController", queryKey, "INQUIRY");
        return this.elementaryTransaction.ANETQueryCapitalAdequacyFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
