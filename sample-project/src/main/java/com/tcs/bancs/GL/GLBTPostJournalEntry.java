package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: GLBTPostJournalEntry
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class GLBTPostJournalEntry {

    private final GLDGLedgerGrabber dataGrabber;
    private final GeneralLedgerService service;

    public GLBTPostJournalEntry() {
        this.dataGrabber = new GLDGLedgerGrabber();
        this.service = new GeneralLedgerService();
    }

    public GLBTPostJournalEntry(GLDGLedgerGrabber dataGrabber, GeneralLedgerService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: GLBTPostJournalEntryExecute
     */
    public MO_OUT_JournalEntry GLBTPostJournalEntryExecute(MO_INP_JournalEntry req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "GLBTPostJournalEntry");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "GLBTPostJournalEntry");
        }

        // Step 2: Data Grabber state query
        LedgerAccount entity = this.dataGrabber.fetchLedgerAccountById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: GL -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "GLBTPostJournalEntry", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "GLBTPostJournalEntry", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("GLBTPostJournalEntry.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_JournalEntry resp = new MO_OUT_JournalEntry();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
