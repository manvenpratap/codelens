package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: ExposureMonitorController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class ExposureMonitorController {

    private final MarketRiskService service;

    public ExposureMonitorController() {
        this.service = new MarketRiskService();
    }

    public ExposureMonitorController(MarketRiskService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: RKBTRecalculateExposure
     */
    public MO_OUT_ExposureRecalculate RKBTRecalculateExposure(MO_INP_ExposureRecalculate request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ExposureMonitorController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.RKBTRecalculateExposure(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: RKETQueryAmlStatus
     */
    public MO_OUT_ExposureRecalculate RKETQueryAmlStatus(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ExposureMonitorController", queryKey, "INQUIRY");
        return this.service.RKETQueryAmlStatus(queryKey);
    }

    /**
     * Generic execute request handler delegating to RKBTRecalculateExposure.
     */
    public MO_OUT_ExposureRecalculate handleExecuteRequest(MO_INP_ExposureRecalculate request) {
        return this.RKBTRecalculateExposure(request);
    }

    /**
     * Generic inquiry request handler delegating to RKETQueryAmlStatus.
     */
    public MO_OUT_ExposureRecalculate handleInquiryRequest(String queryKey) {
        return this.RKETQueryAmlStatus(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
