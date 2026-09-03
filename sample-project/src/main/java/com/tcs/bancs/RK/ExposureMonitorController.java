package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: ExposureMonitorController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class ExposureMonitorController {

    private final RKBTRecalculateExposure businessTransaction;
    private final RKETQueryAmlStatus elementaryTransaction;

    public ExposureMonitorController() {
        this.businessTransaction = new RKBTRecalculateExposure();
        this.elementaryTransaction = new RKETQueryAmlStatus();
    }

    public ExposureMonitorController(RKBTRecalculateExposure bt, RKETQueryAmlStatus et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_ExposureRecalculate handleExecuteRequest(MO_INP_ExposureRecalculate request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ExposureMonitorController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.RKBTRecalculateExposureExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_ExposureRecalculate handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ExposureMonitorController", queryKey, "INQUIRY");
        return this.elementaryTransaction.RKETQueryAmlStatusFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
