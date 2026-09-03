package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: KycComplianceController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class KycComplianceController {

    private final CUBTVerifyKyc businessTransaction;
    private final CUETQueryKycStatus elementaryTransaction;

    public KycComplianceController() {
        this.businessTransaction = new CUBTVerifyKyc();
        this.elementaryTransaction = new CUETQueryKycStatus();
    }

    public KycComplianceController(CUBTVerifyKyc bt, CUETQueryKycStatus et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_KycSubmission handleExecuteRequest(MO_INP_KycSubmission request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "KycComplianceController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.CUBTVerifyKycExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_KycSubmission handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "KycComplianceController", queryKey, "INQUIRY");
        return this.elementaryTransaction.CUETQueryKycStatusFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
