package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.CL.*;

/**
 * TCS BaNCS Business Transaction: TRBTAllocatePosition
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class TRBTAllocatePosition {

    private final TRDGMarketQuoteGrabber dataGrabber;
    private final PositionTrackingService service;

    public TRBTAllocatePosition() {
        this.dataGrabber = new TRDGMarketQuoteGrabber();
        this.service = new PositionTrackingService();
    }

    public TRBTAllocatePosition(TRDGMarketQuoteGrabber dataGrabber, PositionTrackingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: TRBTAllocatePositionExecute
     */
    public MO_OUT_QuoteRequest TRBTAllocatePositionExecute(MO_INP_QuoteRequest req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "TRBTAllocatePosition");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "TRBTAllocatePosition");
        }

        // Step 2: Data Grabber state query
        TradingStrategyConfig entity = this.dataGrabber.fetchTradingStrategyConfigById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: TR -> CL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "TRBTAllocatePosition", req.getMessageCorrelationId(), "CL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "TRBTAllocatePosition", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("TRBTAllocatePosition.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_QuoteRequest resp = new MO_OUT_QuoteRequest();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
