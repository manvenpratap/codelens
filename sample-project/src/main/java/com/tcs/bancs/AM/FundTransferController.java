package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: FundTransferController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class FundTransferController {

    private final AccountService service;

    public FundTransferController() {
        this.service = new AccountService();
    }

    public FundTransferController(AccountService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: AMBTFundTransfer
     */
    public MO_OUT_FundTransfer AMBTFundTransfer(MO_INP_FundTransfer request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FundTransferController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.AMBTFundTransfer(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: AMETQueryAccountDetails
     */
    public MO_OUT_FundTransfer AMETQueryAccountDetails(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FundTransferController", queryKey, "INQUIRY");
        return this.service.AMETQueryAccountDetails(queryKey);
    }

    /**
     * Generic execute request handler delegating to AMBTFundTransfer.
     */
    public MO_OUT_FundTransfer handleExecuteRequest(MO_INP_FundTransfer request) {
        return this.AMBTFundTransfer(request);
    }

    /**
     * Generic inquiry request handler delegating to AMETQueryAccountDetails.
     */
    public MO_OUT_FundTransfer handleInquiryRequest(String queryKey) {
        return this.AMETQueryAccountDetails(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
