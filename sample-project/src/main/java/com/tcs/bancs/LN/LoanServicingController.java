package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: LoanServicingController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class LoanServicingController {

    private final LoanOriginationService service;

    public LoanServicingController() {
        this.service = new LoanOriginationService();
    }

    public LoanServicingController(LoanOriginationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: LNBTDisburseLoan
     */
    public MO_OUT_LoanDisbursement LNBTDisburseLoan(MO_INP_LoanDisbursement request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanServicingController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.LNBTDisburseLoan(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: LNETGetLoanSummary
     */
    public MO_OUT_LoanDisbursement LNETGetLoanSummary(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanServicingController", queryKey, "INQUIRY");
        return this.service.LNETGetLoanSummary(queryKey);
    }

    /**
     * Generic execute request handler delegating to LNBTDisburseLoan.
     */
    public MO_OUT_LoanDisbursement handleExecuteRequest(MO_INP_LoanDisbursement request) {
        return this.LNBTDisburseLoan(request);
    }

    /**
     * Generic inquiry request handler delegating to LNETGetLoanSummary.
     */
    public MO_OUT_LoanDisbursement handleInquiryRequest(String queryKey) {
        return this.LNETGetLoanSummary(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
