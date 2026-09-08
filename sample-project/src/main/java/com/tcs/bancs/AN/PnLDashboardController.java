package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PnLDashboardController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class PnLDashboardController {

    private final PnLCalculationService service;

    public PnLDashboardController() {
        this.service = new PnLCalculationService();
    }

    public PnLDashboardController(PnLCalculationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: ANBTCalibrateYieldCurve
     */
    public MO_OUT_YieldCurveQuery ANBTCalibrateYieldCurve(MO_INP_YieldCurveQuery request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PnLDashboardController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.ANBTCalibrateYieldCurve(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: ANETFetchYieldCurve
     */
    public MO_OUT_YieldCurveQuery ANETFetchYieldCurve(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PnLDashboardController", queryKey, "INQUIRY");
        return this.service.ANETFetchYieldCurve(queryKey);
    }

    /**
     * Generic execute request handler delegating to ANBTCalibrateYieldCurve.
     */
    public MO_OUT_YieldCurveQuery handleExecuteRequest(MO_INP_YieldCurveQuery request) {
        return this.ANBTCalibrateYieldCurve(request);
    }

    /**
     * Generic inquiry request handler delegating to ANETFetchYieldCurve.
     */
    public MO_OUT_YieldCurveQuery handleInquiryRequest(String queryKey) {
        return this.ANETFetchYieldCurve(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
