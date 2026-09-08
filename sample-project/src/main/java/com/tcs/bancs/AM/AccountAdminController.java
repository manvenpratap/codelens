package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: AccountAdminController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class AccountAdminController {

    private final AccountService service;

    public AccountAdminController() {
        this.service = new AccountService();
    }

    public AccountAdminController(AccountService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: AMBTCloseAccount
     */
    public MO_OUT_BalanceInquiry AMBTCloseAccount(MO_INP_BalanceInquiry request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountAdminController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.AMBTCloseAccount(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: AMETSearchStatements
     */
    public MO_OUT_BalanceInquiry AMETSearchStatements(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountAdminController", queryKey, "INQUIRY");
        return this.service.AMETSearchStatements(queryKey);
    }

    /**
     * Generic execute request handler delegating to AMBTCloseAccount.
     */
    public MO_OUT_BalanceInquiry handleExecuteRequest(MO_INP_BalanceInquiry request) {
        return this.AMBTCloseAccount(request);
    }

    /**
     * Generic inquiry request handler delegating to AMETSearchStatements.
     */
    public MO_OUT_BalanceInquiry handleInquiryRequest(String queryKey) {
        return this.AMETSearchStatements(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
