package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: MSBTDispatchOutboundMessage
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class MSBTDispatchOutboundMessage {

    private final MSDGAuditQueueGrabber dataGrabber;
    private final Iso20022TransformationService service;

    public MSBTDispatchOutboundMessage() {
        this.dataGrabber = new MSDGAuditQueueGrabber();
        this.service = new Iso20022TransformationService();
    }

    public MSBTDispatchOutboundMessage(MSDGAuditQueueGrabber dataGrabber, Iso20022TransformationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: MSBTDispatchOutboundMessageExecute
     */
    public MO_OUT_IsoPacs008 MSBTDispatchOutboundMessageExecute(MO_INP_IsoPacs008 req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "MSBTDispatchOutboundMessage");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "MSBTDispatchOutboundMessage");
        }

        // Step 2: Data Grabber state query
        InboundPayloadStore entity = this.dataGrabber.fetchInboundPayloadStoreById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: MS -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "MSBTDispatchOutboundMessage", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "MSBTDispatchOutboundMessage", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("MSBTDispatchOutboundMessage.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_IsoPacs008 resp = new MO_OUT_IsoPacs008();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
