package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: AnalyticsReportingController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class AnalyticsReportingController {

    private final ANBTCalculatePnL businessTransaction;
    private final ANETGetPnLSummary elementaryTransaction;

    public AnalyticsReportingController() {
        this.businessTransaction = new ANBTCalculatePnL();
        this.elementaryTransaction = new ANETGetPnLSummary();
    }

    public AnalyticsReportingController(ANBTCalculatePnL bt, ANETGetPnLSummary et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_PnLCalculation handleExecuteRequest(MO_INP_PnLCalculation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AnalyticsReportingController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.ANBTCalculatePnLExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_PnLCalculation handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AnalyticsReportingController", queryKey, "INQUIRY");
        return this.elementaryTransaction.ANETGetPnLSummaryFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
