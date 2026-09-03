package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PnLDashboardController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class PnLDashboardController {

    private final ANBTCalibrateYieldCurve businessTransaction;
    private final ANETFetchYieldCurve elementaryTransaction;

    public PnLDashboardController() {
        this.businessTransaction = new ANBTCalibrateYieldCurve();
        this.elementaryTransaction = new ANETFetchYieldCurve();
    }

    public PnLDashboardController(ANBTCalibrateYieldCurve bt, ANETFetchYieldCurve et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_YieldCurveQuery handleExecuteRequest(MO_INP_YieldCurveQuery request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PnLDashboardController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.ANBTCalibrateYieldCurveExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_YieldCurveQuery handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PnLDashboardController", queryKey, "INQUIRY");
        return this.elementaryTransaction.ANETFetchYieldCurveFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
