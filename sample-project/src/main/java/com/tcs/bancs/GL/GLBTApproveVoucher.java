package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: GLBTApproveVoucher
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class GLBTApproveVoucher {

    private final GLDGVoucherGrabber dataGrabber;
    private final JournalPostingService service;

    public GLBTApproveVoucher() {
        this.dataGrabber = new GLDGVoucherGrabber();
        this.service = new JournalPostingService();
    }

    public GLBTApproveVoucher(GLDGVoucherGrabber dataGrabber, JournalPostingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: GLBTApproveVoucherExecute
     */
    public MO_OUT_TrialBalanceQuery GLBTApproveVoucherExecute(MO_INP_TrialBalanceQuery req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "GLBTApproveVoucher");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "GLBTApproveVoucher");
        }

        // Step 2: Data Grabber state query
        JournalVoucher entity = this.dataGrabber.fetchJournalVoucherById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: GL -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "GLBTApproveVoucher", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "GLBTApproveVoucher", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("GLBTApproveVoucher.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_TrialBalanceQuery resp = new MO_OUT_TrialBalanceQuery();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
