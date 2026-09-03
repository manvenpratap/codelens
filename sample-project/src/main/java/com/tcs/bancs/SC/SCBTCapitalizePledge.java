package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.LN.*;

/**
 * TCS BaNCS Business Transaction: SCBTCapitalizePledge
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class SCBTCapitalizePledge {

    private final SCDGMarginCallGrabber dataGrabber;
    private final LtvMonitoringService service;

    public SCBTCapitalizePledge() {
        this.dataGrabber = new SCDGMarginCallGrabber();
        this.service = new LtvMonitoringService();
    }

    public SCBTCapitalizePledge(SCDGMarginCallGrabber dataGrabber, LtvMonitoringService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: SCBTCapitalizePledgeExecute
     */
    public MO_OUT_PledgeCreation SCBTCapitalizePledgeExecute(MO_INP_PledgeCreation req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "SCBTCapitalizePledge");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "SCBTCapitalizePledge");
        }

        // Step 2: Data Grabber state query
        MarginCallEvent entity = this.dataGrabber.fetchMarginCallEventById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: SC -> LN
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "SCBTCapitalizePledge", req.getMessageCorrelationId(), "LN");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "SCBTCapitalizePledge", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("SCBTCapitalizePledge.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_PledgeCreation resp = new MO_OUT_PledgeCreation();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
