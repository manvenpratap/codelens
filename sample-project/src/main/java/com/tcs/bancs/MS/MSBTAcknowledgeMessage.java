package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: MSBTAcknowledgeMessage
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class MSBTAcknowledgeMessage {

    private final MSDGRoutingGrabber dataGrabber;
    private final MessageRoutingService service;

    public MSBTAcknowledgeMessage() {
        this.dataGrabber = new MSDGRoutingGrabber();
        this.service = new MessageRoutingService();
    }

    public MSBTAcknowledgeMessage(MSDGRoutingGrabber dataGrabber, MessageRoutingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: MSBTAcknowledgeMessageExecute
     */
    public MO_OUT_TransformMessage MSBTAcknowledgeMessageExecute(MO_INP_TransformMessage req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "MSBTAcknowledgeMessage");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "MSBTAcknowledgeMessage");
        }

        // Step 2: Data Grabber state query
        TransformationRule entity = this.dataGrabber.fetchTransformationRuleById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: MS -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "MSBTAcknowledgeMessage", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "MSBTAcknowledgeMessage", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("MSBTAcknowledgeMessage.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_TransformMessage resp = new MO_OUT_TransformMessage();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
