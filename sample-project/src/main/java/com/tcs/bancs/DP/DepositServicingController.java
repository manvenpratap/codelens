package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: DepositServicingController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class DepositServicingController {

    private final DepositBookingService service;

    public DepositServicingController() {
        this.service = new DepositBookingService();
    }

    public DepositServicingController(DepositBookingService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: DPBTLiquidatePrematurely
     */
    public MO_OUT_PrematureWithdrawal DPBTLiquidatePrematurely(MO_INP_PrematureWithdrawal request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositServicingController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.DPBTLiquidatePrematurely(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: DPETSimulateMaturityValue
     */
    public MO_OUT_PrematureWithdrawal DPETSimulateMaturityValue(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositServicingController", queryKey, "INQUIRY");
        return this.service.DPETSimulateMaturityValue(queryKey);
    }

    /**
     * Generic execute request handler delegating to DPBTLiquidatePrematurely.
     */
    public MO_OUT_PrematureWithdrawal handleExecuteRequest(MO_INP_PrematureWithdrawal request) {
        return this.DPBTLiquidatePrematurely(request);
    }

    /**
     * Generic inquiry request handler delegating to DPETSimulateMaturityValue.
     */
    public MO_OUT_PrematureWithdrawal handleInquiryRequest(String queryKey) {
        return this.DPETSimulateMaturityValue(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
