package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.GL.*;

/**
 * TCS BaNCS Business Transaction: AMBTCloseAccount
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class AMBTCloseAccount {

    private final AMDGLimitGrabber dataGrabber;
    private final FeeAssessmentService service;

    public AMBTCloseAccount() {
        this.dataGrabber = new AMDGLimitGrabber();
        this.service = new FeeAssessmentService();
    }

    public AMBTCloseAccount(AMDGLimitGrabber dataGrabber, FeeAssessmentService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: AMBTCloseAccountExecute
     */
    public MO_OUT_BalanceInquiry AMBTCloseAccountExecute(MO_INP_BalanceInquiry req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "AMBTCloseAccount");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "AMBTCloseAccount");
        }

        // Step 2: Data Grabber state query
        OverdraftFacility entity = this.dataGrabber.fetchOverdraftFacilityById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: AM -> GL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "AMBTCloseAccount", req.getMessageCorrelationId(), "GL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "AMBTCloseAccount", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("AMBTCloseAccount.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_BalanceInquiry resp = new MO_OUT_BalanceInquiry();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
