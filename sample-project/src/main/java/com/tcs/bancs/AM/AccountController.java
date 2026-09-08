package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: AccountController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class AccountController {

    private final AccountService service;

    public AccountController() {
        this.service = new AccountService();
    }

    public AccountController(AccountService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: AMBTAccountOpen
     */
    public MO_OUT_AccountOpen AMBTAccountOpen(MO_INP_AccountOpen request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.AMBTAccountOpen(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: AMETFetchBalance
     */
    public MO_OUT_AccountOpen AMETFetchBalance(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "AccountController", queryKey, "INQUIRY");
        return this.service.AMETFetchBalance(queryKey);
    }

    /**
     * Generic execute request handler delegating to AMBTAccountOpen.
     */
    public MO_OUT_AccountOpen handleExecuteRequest(MO_INP_AccountOpen request) {
        return this.AMBTAccountOpen(request);
    }

    /**
     * Generic inquiry request handler delegating to AMETFetchBalance.
     */
    public MO_OUT_AccountOpen handleInquiryRequest(String queryKey) {
        return this.AMETFetchBalance(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
