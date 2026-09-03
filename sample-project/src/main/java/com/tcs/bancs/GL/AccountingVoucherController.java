package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: AccountingVoucherController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class AccountingVoucherController {

    private final GLBTApproveVoucher businessTransaction;
    private final GLETFetchTrialBalance elementaryTransaction;

    public AccountingVoucherController() {
        this.businessTransaction = new GLBTApproveVoucher();
        this.elementaryTransaction = new GLETFetchTrialBalance();
    }

    public AccountingVoucherController(GLBTApproveVoucher bt, GLETFetchTrialBalance et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_TrialBalanceQuery handleExecuteRequest(MO_INP_TrialBalanceQuery request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountingVoucherController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.GLBTApproveVoucherExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_TrialBalanceQuery handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountingVoucherController", queryKey, "INQUIRY");
        return this.elementaryTransaction.GLETFetchTrialBalanceFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
