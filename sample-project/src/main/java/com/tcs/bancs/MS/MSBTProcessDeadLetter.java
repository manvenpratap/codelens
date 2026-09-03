package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: MSBTProcessDeadLetter
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class MSBTProcessDeadLetter {

    private final MSDGMessageGrabber dataGrabber;
    private final DeadLetterQueueService service;

    public MSBTProcessDeadLetter() {
        this.dataGrabber = new MSDGMessageGrabber();
        this.service = new DeadLetterQueueService();
    }

    public MSBTProcessDeadLetter(MSDGMessageGrabber dataGrabber, DeadLetterQueueService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: MSBTProcessDeadLetterExecute
     */
    public MO_DispatchReceipt MSBTProcessDeadLetterExecute(MO_MessageEnvelope req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "MSBTProcessDeadLetter");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "MSBTProcessDeadLetter");
        }

        // Step 2: Data Grabber state query
        MessageHeaderRecord entity = this.dataGrabber.fetchMessageHeaderRecordById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: MS -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "MSBTProcessDeadLetter", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "MSBTProcessDeadLetter", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("MSBTProcessDeadLetter.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_DispatchReceipt resp = new MO_DispatchReceipt();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
