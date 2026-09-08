package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: DepositMaturityController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class DepositMaturityController {

    private final DepositBookingService service;

    public DepositMaturityController() {
        this.service = new DepositBookingService();
    }

    public DepositMaturityController(DepositBookingService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: DPBTMatureDeposit
     */
    public MO_OUT_MaturityInstruction DPBTMatureDeposit(MO_INP_MaturityInstruction request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositMaturityController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.DPBTMatureDeposit(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: DPETCalculateBreakValue
     */
    public MO_OUT_MaturityInstruction DPETCalculateBreakValue(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositMaturityController", queryKey, "INQUIRY");
        return this.service.DPETCalculateBreakValue(queryKey);
    }

    /**
     * Generic execute request handler delegating to DPBTMatureDeposit.
     */
    public MO_OUT_MaturityInstruction handleExecuteRequest(MO_INP_MaturityInstruction request) {
        return this.DPBTMatureDeposit(request);
    }

    /**
     * Generic inquiry request handler delegating to DPETCalculateBreakValue.
     */
    public MO_OUT_MaturityInstruction handleInquiryRequest(String queryKey) {
        return this.DPETCalculateBreakValue(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
