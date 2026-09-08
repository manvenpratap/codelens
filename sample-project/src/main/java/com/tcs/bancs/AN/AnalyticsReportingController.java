package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: AnalyticsReportingController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class AnalyticsReportingController {

    private final PnLCalculationService service;

    public AnalyticsReportingController() {
        this.service = new PnLCalculationService();
    }

    public AnalyticsReportingController(PnLCalculationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: ANBTCalculatePnL
     */
    public MO_OUT_PnLCalculation ANBTCalculatePnL(MO_INP_PnLCalculation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AnalyticsReportingController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.ANBTCalculatePnL(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: ANETGetPnLSummary
     */
    public MO_OUT_PnLCalculation ANETGetPnLSummary(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AnalyticsReportingController", queryKey, "INQUIRY");
        return this.service.ANETGetPnLSummary(queryKey);
    }

    /**
     * Generic execute request handler delegating to ANBTCalculatePnL.
     */
    public MO_OUT_PnLCalculation handleExecuteRequest(MO_INP_PnLCalculation request) {
        return this.ANBTCalculatePnL(request);
    }

    /**
     * Generic inquiry request handler delegating to ANETGetPnLSummary.
     */
    public MO_OUT_PnLCalculation handleInquiryRequest(String queryKey) {
        return this.ANETGetPnLSummary(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
