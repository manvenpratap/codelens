package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: AccountController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class AccountController {

    private final AMBTAccountOpen businessTransaction;
    private final AMETFetchBalance elementaryTransaction;

    public AccountController() {
        this.businessTransaction = new AMBTAccountOpen();
        this.elementaryTransaction = new AMETFetchBalance();
    }

    public AccountController(AMBTAccountOpen bt, AMETFetchBalance et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_AccountOpen handleExecuteRequest(MO_INP_AccountOpen request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.AMBTAccountOpenExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_AccountOpen handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountController", queryKey, "INQUIRY");
        return this.elementaryTransaction.AMETFetchBalanceFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
