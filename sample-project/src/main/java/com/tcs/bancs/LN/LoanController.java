package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: LoanController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class LoanController {

    private final LoanOriginationService service;

    public LoanController() {
        this.service = new LoanOriginationService();
    }

    public LoanController(LoanOriginationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: LNBTLoanOrigination
     */
    public MO_OUT_LoanApplication LNBTLoanOrigination(MO_INP_LoanApplication request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.LNBTLoanOrigination(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: LNETQuerySchedule
     */
    public MO_OUT_LoanApplication LNETQuerySchedule(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanController", queryKey, "INQUIRY");
        return this.service.LNETQuerySchedule(queryKey);
    }

    /**
     * Generic execute request handler delegating to LNBTLoanOrigination.
     */
    public MO_OUT_LoanApplication handleExecuteRequest(MO_INP_LoanApplication request) {
        return this.LNBTLoanOrigination(request);
    }

    /**
     * Generic inquiry request handler delegating to LNETQuerySchedule.
     */
    public MO_OUT_LoanApplication handleInquiryRequest(String queryKey) {
        return this.LNETQuerySchedule(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
