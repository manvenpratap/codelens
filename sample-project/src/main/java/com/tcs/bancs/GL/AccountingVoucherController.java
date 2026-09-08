package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: AccountingVoucherController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class AccountingVoucherController {

    private final GeneralLedgerService service;

    public AccountingVoucherController() {
        this.service = new GeneralLedgerService();
    }

    public AccountingVoucherController(GeneralLedgerService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: GLBTApproveVoucher
     */
    public MO_OUT_TrialBalanceQuery GLBTApproveVoucher(MO_INP_TrialBalanceQuery request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountingVoucherController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.GLBTApproveVoucher(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: GLETFetchTrialBalance
     */
    public MO_OUT_TrialBalanceQuery GLETFetchTrialBalance(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountingVoucherController", queryKey, "INQUIRY");
        return this.service.GLETFetchTrialBalance(queryKey);
    }

    /**
     * Generic execute request handler delegating to GLBTApproveVoucher.
     */
    public MO_OUT_TrialBalanceQuery handleExecuteRequest(MO_INP_TrialBalanceQuery request) {
        return this.GLBTApproveVoucher(request);
    }

    /**
     * Generic inquiry request handler delegating to GLETFetchTrialBalance.
     */
    public MO_OUT_TrialBalanceQuery handleInquiryRequest(String queryKey) {
        return this.GLETFetchTrialBalance(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
