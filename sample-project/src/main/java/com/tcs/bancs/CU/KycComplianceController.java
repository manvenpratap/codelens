package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: KycComplianceController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class KycComplianceController {

    private final CustomerOnboardingService service;

    public KycComplianceController() {
        this.service = new CustomerOnboardingService();
    }

    public KycComplianceController(CustomerOnboardingService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: CUBTVerifyKyc
     */
    public MO_OUT_KycSubmission CUBTVerifyKyc(MO_INP_KycSubmission request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "KycComplianceController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.CUBTVerifyKyc(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: CUETQueryKycStatus
     */
    public MO_OUT_KycSubmission CUETQueryKycStatus(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "KycComplianceController", queryKey, "INQUIRY");
        return this.service.CUETQueryKycStatus(queryKey);
    }

    /**
     * Generic execute request handler delegating to CUBTVerifyKyc.
     */
    public MO_OUT_KycSubmission handleExecuteRequest(MO_INP_KycSubmission request) {
        return this.CUBTVerifyKyc(request);
    }

    /**
     * Generic inquiry request handler delegating to CUETQueryKycStatus.
     */
    public MO_OUT_KycSubmission handleInquiryRequest(String queryKey) {
        return this.CUETQueryKycStatus(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
