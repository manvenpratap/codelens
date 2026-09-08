package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: LoanScheduleController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class LoanScheduleController {

    private final LoanOriginationService service;

    public LoanScheduleController() {
        this.service = new LoanOriginationService();
    }

    public LoanScheduleController(LoanOriginationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: LNBTProcessRepayment
     */
    public MO_OUT_LoanRepayment LNBTProcessRepayment(MO_INP_LoanRepayment request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanScheduleController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.LNBTProcessRepayment(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: LNETCalculateForeclosure
     */
    public MO_OUT_LoanRepayment LNETCalculateForeclosure(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanScheduleController", queryKey, "INQUIRY");
        return this.service.LNETCalculateForeclosure(queryKey);
    }

    /**
     * Generic execute request handler delegating to LNBTProcessRepayment.
     */
    public MO_OUT_LoanRepayment handleExecuteRequest(MO_INP_LoanRepayment request) {
        return this.LNBTProcessRepayment(request);
    }

    /**
     * Generic inquiry request handler delegating to LNETCalculateForeclosure.
     */
    public MO_OUT_LoanRepayment handleInquiryRequest(String queryKey) {
        return this.LNETCalculateForeclosure(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
