package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: RegulatoryComplianceController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class RegulatoryComplianceController {

    private final PnLCalculationService service;

    public RegulatoryComplianceController() {
        this.service = new PnLCalculationService();
    }

    public RegulatoryComplianceController(PnLCalculationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: ANBTGenerateRegulatoryFiling
     */
    public MO_OUT_BaselReportGenerate ANBTGenerateRegulatoryFiling(MO_INP_BaselReportGenerate request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "RegulatoryComplianceController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.ANBTGenerateRegulatoryFiling(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: ANETQueryCapitalAdequacy
     */
    public MO_OUT_BaselReportGenerate ANETQueryCapitalAdequacy(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "RegulatoryComplianceController", queryKey, "INQUIRY");
        return this.service.ANETQueryCapitalAdequacy(queryKey);
    }

    /**
     * Generic execute request handler delegating to ANBTGenerateRegulatoryFiling.
     */
    public MO_OUT_BaselReportGenerate handleExecuteRequest(MO_INP_BaselReportGenerate request) {
        return this.ANBTGenerateRegulatoryFiling(request);
    }

    /**
     * Generic inquiry request handler delegating to ANETQueryCapitalAdequacy.
     */
    public MO_OUT_BaselReportGenerate handleInquiryRequest(String queryKey) {
        return this.ANETQueryCapitalAdequacy(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
