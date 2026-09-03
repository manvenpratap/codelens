package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: MSBTTransformPayload
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class MSBTTransformPayload {

    private final MSDGPayloadGrabber dataGrabber;
    private final FixEngineIntegrationService service;

    public MSBTTransformPayload() {
        this.dataGrabber = new MSDGPayloadGrabber();
        this.service = new FixEngineIntegrationService();
    }

    public MSBTTransformPayload(MSDGPayloadGrabber dataGrabber, FixEngineIntegrationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: MSBTTransformPayloadExecute
     */
    public MO_OUT_FixExecutionReport MSBTTransformPayloadExecute(MO_INP_FixNewOrderSingle req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "MSBTTransformPayload");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "MSBTTransformPayload");
        }

        // Step 2: Data Grabber state query
        OutboundDispatchQueue entity = this.dataGrabber.fetchOutboundDispatchQueueById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: MS -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "MSBTTransformPayload", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "MSBTTransformPayload", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("MSBTTransformPayload.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_FixExecutionReport resp = new MO_OUT_FixExecutionReport();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
