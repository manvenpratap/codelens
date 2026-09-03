package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.CL.*;

/**
 * TCS BaNCS Business Transaction: TRBTExecuteTrade
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class TRBTExecuteTrade {

    private final TRDGPortfolioGrabber dataGrabber;
    private final ExecutionReportingService service;

    public TRBTExecuteTrade() {
        this.dataGrabber = new TRDGPortfolioGrabber();
        this.service = new ExecutionReportingService();
    }

    public TRBTExecuteTrade(TRDGPortfolioGrabber dataGrabber, ExecutionReportingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: TRBTExecuteTradeExecute
     */
    public MO_OUT_TradeAllocation TRBTExecuteTradeExecute(MO_INP_TradeAllocation req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "TRBTExecuteTrade");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "TRBTExecuteTrade");
        }

        // Step 2: Data Grabber state query
        PortfolioHolding entity = this.dataGrabber.fetchPortfolioHoldingById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: TR -> CL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "TRBTExecuteTrade", req.getMessageCorrelationId(), "CL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "TRBTExecuteTrade", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("TRBTExecuteTrade.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_TradeAllocation resp = new MO_OUT_TradeAllocation();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
