package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: LoanScheduleController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class LoanScheduleController {

    private final LNBTProcessRepayment businessTransaction;
    private final LNETCalculateForeclosure elementaryTransaction;

    public LoanScheduleController() {
        this.businessTransaction = new LNBTProcessRepayment();
        this.elementaryTransaction = new LNETCalculateForeclosure();
    }

    public LoanScheduleController(LNBTProcessRepayment bt, LNETCalculateForeclosure et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_LoanRepayment handleExecuteRequest(MO_INP_LoanRepayment request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanScheduleController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.LNBTProcessRepaymentExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_LoanRepayment handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanScheduleController", queryKey, "INQUIRY");
        return this.elementaryTransaction.LNETCalculateForeclosureFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
