package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: LoanServicingController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class LoanServicingController {

    private final LNBTDisburseLoan businessTransaction;
    private final LNETGetLoanSummary elementaryTransaction;

    public LoanServicingController() {
        this.businessTransaction = new LNBTDisburseLoan();
        this.elementaryTransaction = new LNETGetLoanSummary();
    }

    public LoanServicingController(LNBTDisburseLoan bt, LNETGetLoanSummary et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_LoanDisbursement handleExecuteRequest(MO_INP_LoanDisbursement request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanServicingController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.LNBTDisburseLoanExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_LoanDisbursement handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanServicingController", queryKey, "INQUIRY");
        return this.elementaryTransaction.LNETGetLoanSummaryFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
