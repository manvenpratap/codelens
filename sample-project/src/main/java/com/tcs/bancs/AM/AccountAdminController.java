package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: AccountAdminController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class AccountAdminController {

    private final AMBTCloseAccount businessTransaction;
    private final AMETSearchStatements elementaryTransaction;

    public AccountAdminController() {
        this.businessTransaction = new AMBTCloseAccount();
        this.elementaryTransaction = new AMETSearchStatements();
    }

    public AccountAdminController(AMBTCloseAccount bt, AMETSearchStatements et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_BalanceInquiry handleExecuteRequest(MO_INP_BalanceInquiry request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountAdminController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.AMBTCloseAccountExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_BalanceInquiry handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountAdminController", queryKey, "INQUIRY");
        return this.elementaryTransaction.AMETSearchStatementsFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
