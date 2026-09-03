package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: LoanController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class LoanController {

    private final LNBTLoanOrigination businessTransaction;
    private final LNETQuerySchedule elementaryTransaction;

    public LoanController() {
        this.businessTransaction = new LNBTLoanOrigination();
        this.elementaryTransaction = new LNETQuerySchedule();
    }

    public LoanController(LNBTLoanOrigination bt, LNETQuerySchedule et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_LoanApplication handleExecuteRequest(MO_INP_LoanApplication request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.LNBTLoanOriginationExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_LoanApplication handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "LoanController", queryKey, "INQUIRY");
        return this.elementaryTransaction.LNETQueryScheduleFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
