package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: MSBTRouteInboundMessage
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class MSBTRouteInboundMessage {

    private final MSDGMessageGrabber dataGrabber;
    private final SwiftParserService service;

    public MSBTRouteInboundMessage() {
        this.dataGrabber = new MSDGMessageGrabber();
        this.service = new SwiftParserService();
    }

    public MSBTRouteInboundMessage(MSDGMessageGrabber dataGrabber, SwiftParserService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: MSBTRouteInboundMessageExecute
     */
    public MO_OUT_SwiftMT103 MSBTRouteInboundMessageExecute(MO_INP_SwiftMT103 req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "MSBTRouteInboundMessage");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "MSBTRouteInboundMessage");
        }

        // Step 2: Data Grabber state query
        MessageHeaderRecord entity = this.dataGrabber.fetchMessageHeaderRecordById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: MS -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "MSBTRouteInboundMessage", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "MSBTRouteInboundMessage", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("MSBTRouteInboundMessage.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_SwiftMT103 resp = new MO_OUT_SwiftMT103();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
